package com.medcore.clinical.encounter.write

import com.medcore.clinical.encounter.model.EncounterStatus
import com.medcore.clinical.encounter.persistence.EncounterEntity
import com.medcore.clinical.encounter.persistence.EncounterRepository
import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.platform.write.WriteConflictException
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component

/**
 * Handler for [StartEncounterCommand] (Phase 4C.1, VS1 Chunk D).
 *
 * Runs inside the [com.medcore.platform.write.WriteGate]-owned
 * transaction, after [StartEncounterPolicy] has approved
 * `ENCOUNTER_WRITE`. `PhiRlsTxHook` has set both RLS GUCs
 * (`app.current_tenant_id` + `app.current_user_id`) by the time
 * this handler runs, so every DB touch lands under the caller's
 * RLS envelope.
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug (invariant — policy already checked
 *    ACTIVE membership via the authority resolver, so the tenant
 *    is always visible to the caller here; a miss indicates data
 *    drift and is treated as NotFound).
 * 2. Verify the patient exists AND belongs to the same tenant.
 *    RLS already hides cross-tenant and soft-deleted patients;
 *    this check converts an RLS-invisible patient into a clean
 *    404 with no existence leak. Mirrors 4A.4 `GetPatientHandler`
 *    belt-and-braces discipline.
 * 3. Mint the encounter UUID, stamp `started_at = now()`, INSERT
 *    with `status = IN_PROGRESS` and `encounter_class` from the
 *    command. `saveAndFlush` so `row_version` is populated before
 *    the auditor runs.
 * 4. Return [EncounterSnapshot].
 *
 * ### No `@Transactional`
 *
 * WriteGate owns the transaction (Phase 3J.1, clinical-write-
 * pattern §4). Handlers assume the gate opened it — nested-tx
 * is forbidden.
 */
@Component
class StartEncounterHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
    private val clock: Clock,
) {

    fun handle(
        command: StartEncounterCommand,
        context: WriteContext,
    ): EncounterSnapshot {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val patient = patientRepository.findById(command.patientId).orElse(null)
            ?: throw EntityNotFoundException("patient not found: ${command.patientId}")
        if (patient.tenantId != tenant.id) {
            // Cross-tenant probe: identical 404 as "not found" —
            // no existence leak.
            throw EntityNotFoundException("patient not found: ${command.patientId}")
        }

        // Phase 4C.4: at most one IN_PROGRESS encounter per
        // (tenant, patient). Pre-check is the user-facing
        // error surface; V22's partial unique index is the
        // race-proof backstop (translated below on violation).
        encounterRepository
            .findInProgressByTenantIdAndPatientId(tenant.id, patient.id)
            ?.let { existing ->
                throw WriteConflictException(
                    code = "encounter_in_progress_exists",
                    details = mapOf("existingEncounterId" to existing.id.toString()),
                )
            }

        val now = Instant.now(clock)
        val entity = EncounterEntity(
            id = UUID.randomUUID(),
            tenantId = tenant.id,
            patientId = patient.id,
            status = EncounterStatus.IN_PROGRESS,
            encounterClass = command.encounterClass,
            startedAt = now,
            finishedAt = null,
            createdAt = now,
            updatedAt = now,
            createdBy = context.principal.userId,
            updatedBy = context.principal.userId,
        )
        val saved = try {
            encounterRepository.saveAndFlush(entity)
        } catch (ex: DataIntegrityViolationException) {
            // Race path: a concurrent Start-encounter from another
            // request (or tab, or double-click) passed the same
            // pre-check and committed first. V22's
            // `uq_clinical_encounter_one_in_progress_per_patient`
            // partial unique index refuses our INSERT. Translate
            // to the same WriteConflictException the sequential
            // path throws so callers see an identical 409 body
            // regardless of which arm fired.
            val winner = encounterRepository
                .findInProgressByTenantIdAndPatientId(tenant.id, patient.id)
            if (winner != null) {
                throw WriteConflictException(
                    code = "encounter_in_progress_exists",
                    cause = ex,
                    details = mapOf(
                        "existingEncounterId" to winner.id.toString(),
                    ),
                )
            }
            // Re-query found no IN_PROGRESS — the violation wasn't
            // our invariant. Re-throw the original; the generic
            // 409 handler takes over (`resource.conflict` without
            // a specific reason).
            throw ex
        }
        return EncounterSnapshot.from(saved)
    }
}
