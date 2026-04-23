package com.medcore.clinical.patient.write

import com.medcore.clinical.patient.model.MrnSource
import com.medcore.clinical.patient.model.PatientStatus
import com.medcore.clinical.patient.mrn.MrnGenerator
import com.medcore.clinical.patient.persistence.PatientEntity
import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.clinical.patient.service.DuplicatePatientDetector
import com.medcore.clinical.patient.service.DuplicatePatientWarningException
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * Handler for [CreatePatientCommand] (Phase 4A.2).
 *
 * Runs inside the [com.medcore.platform.write.WriteGate]-owned
 * transaction, after the [CreatePatientPolicy] has approved
 * PATIENT_CREATE authority. `PhiRlsTxHook` has set both RLS GUCs
 * (`app.current_tenant_id` + `app.current_user_id`) by the time
 * this handler runs, so the repository call lands under the
 * correct row-level-security envelope.
 *
 * ### Flow
 *
 * 1. Load the tenant by slug (RLS-gated — caller holds
 *    `PATIENT_CREATE` which requires ACTIVE membership, so the
 *    tenant is visible).
 * 2. Unless [CreatePatientCommand.confirmDuplicate] is `true`,
 *    run [DuplicatePatientDetector]. If candidates exist, throw
 *    [DuplicatePatientWarningException] → 409 with minimal
 *    candidate info.
 * 3. Mint the MRN via [MrnGenerator.generate]. Atomic upsert
 *    (INSERT ... ON CONFLICT DO UPDATE RETURNING) — monotonic
 *    per tenant, rollback-safe (counter bump rolls back with
 *    the enclosing tx if the INSERT below fails).
 * 4. Build the [PatientEntity], save, flush, return
 *    [PatientSnapshot].
 *
 * ### Why tenant lookup here
 *
 * The command carries the tenant SLUG (caller-facing identifier).
 * Persistence requires the tenant UUID. Policy already resolved
 * authorities by slug, but the handler needs the UUID to stamp
 * `tenant_id` on the patient row. A 404 on slug-not-found is
 * impossible here (policy would have refused) — the load is an
 * invariant check, not a user-facing error path.
 *
 * ### Audit timing
 *
 * Audit emission runs AFTER this handler returns, in the
 * gate's [com.medcore.platform.write.WriteAuditor.onSuccess]
 * call — same transaction, atomic with the mutation (ADR-003 §2).
 */
@Component
class CreatePatientHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
    private val mrnGenerator: MrnGenerator,
    private val duplicatePatientDetector: DuplicatePatientDetector,
    private val clock: Clock,
) {

    fun handle(
        command: CreatePatientCommand,
        context: com.medcore.platform.write.WriteContext,
    ): PatientSnapshot {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        // Duplicate warning — skipped only when caller explicitly
        // acknowledged via the X-Confirm-Duplicate header. Runs
        // BEFORE MRN minting so we don't burn a counter value on a
        // request that's about to 409.
        if (!command.confirmDuplicate) {
            val candidates = duplicatePatientDetector.detect(
                tenantId = tenant.id,
                birthDate = command.birthDate,
                nameFamily = command.nameFamily,
                nameGiven = command.nameGiven,
            )
            if (candidates.isNotEmpty()) {
                throw DuplicatePatientWarningException(candidates)
            }
        }

        val mrn = mrnGenerator.generate(tenant.id)
        val now = Instant.now(clock)
        val id = UUID.randomUUID()

        val entity = PatientEntity(
            id = id,
            tenantId = tenant.id,
            mrn = mrn,
            mrnSource = MrnSource.GENERATED,
            nameGiven = command.nameGiven,
            nameFamily = command.nameFamily,
            nameMiddle = command.nameMiddle,
            nameSuffix = command.nameSuffix,
            namePrefix = command.namePrefix,
            preferredName = command.preferredName,
            birthDate = command.birthDate,
            administrativeSexWire = command.administrativeSex.wireValue,
            sexAssignedAtBirth = command.sexAssignedAtBirth,
            genderIdentityCode = command.genderIdentityCode,
            preferredLanguage = command.preferredLanguage,
            status = PatientStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
            createdBy = context.principal.userId,
            updatedBy = context.principal.userId,
        )
        val saved = patientRepository.saveAndFlush(entity)

        return toSnapshot(saved)
    }

    private fun toSnapshot(entity: PatientEntity): PatientSnapshot = PatientSnapshot(
        id = entity.id,
        tenantId = entity.tenantId,
        mrn = entity.mrn,
        mrnSource = entity.mrnSource,
        nameGiven = entity.nameGiven,
        nameFamily = entity.nameFamily,
        nameMiddle = entity.nameMiddle,
        nameSuffix = entity.nameSuffix,
        namePrefix = entity.namePrefix,
        preferredName = entity.preferredName,
        birthDate = entity.birthDate,
        administrativeSex = entity.administrativeSex,
        sexAssignedAtBirth = entity.sexAssignedAtBirth,
        genderIdentityCode = entity.genderIdentityCode,
        preferredLanguage = entity.preferredLanguage,
        status = entity.status,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        createdBy = entity.createdBy,
        updatedBy = entity.updatedBy,
        rowVersion = entity.rowVersion,
    )
}
