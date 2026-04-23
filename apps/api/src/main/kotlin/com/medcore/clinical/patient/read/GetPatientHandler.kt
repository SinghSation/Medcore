package com.medcore.clinical.patient.read

import com.medcore.clinical.patient.persistence.PatientEntity
import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.clinical.patient.write.PatientSnapshot
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.stereotype.Component

/**
 * Handler for [GetPatientCommand] (Phase 4A.4).
 *
 * Runs inside [com.medcore.platform.read.ReadGate]'s
 * read-only transaction with RLS GUCs set by `PhiRlsTxHook`.
 *
 * ### Flow
 *
 * 1. Load the tenant by slug. Policy already ran; load is
 *    an invariant check.
 * 2. Load the patient by id via
 *    [PatientRepository.findById]. RLS-gated: V14's
 *    `p_patient_select` policy hides `status = 'DELETED'`
 *    rows, cross-tenant rows, and rows where the caller
 *    lacks ACTIVE membership. A hit means "visible patient";
 *    a miss means "not found OR not visible" — **identical
 *    404 response** for both cases (existence-leak
 *    defence).
 * 3. Verify `patient.tenantId == tenant.id` as a belt-and-
 *    braces check against a crafted patient UUID. RLS
 *    already filters cross-tenant rows, but the app-layer
 *    check matches the 4A.2 / 4A.3 precedent.
 * 4. Return a [PatientSnapshot] built from the entity.
 *
 * ### Why NOT `@Transactional`
 *
 * `ReadGate` owns the transaction. `@Transactional` on the
 * handler would create nested-tx ambiguity. The handler
 * assumes the gate has opened a read-only tx and does not
 * open its own. (Carries forward the same discipline as
 * WriteGate handlers.)
 *
 * ### Reuse of [PatientSnapshot]
 *
 * The 4A.2 write-path snapshot shape is reused. Response
 * DTOs (`PatientResponse`) render from the snapshot
 * identically whether the snapshot came from a create,
 * update, or read — same wire contract.
 */
@Component
class GetPatientHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
) {

    fun handle(
        command: GetPatientCommand,
        @Suppress("UNUSED_PARAMETER") context: WriteContext,
    ): PatientSnapshot {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val patient = patientRepository.findById(command.patientId).orElse(null)
            ?: throw EntityNotFoundException("patient not found: ${command.patientId}")
        if (patient.tenantId != tenant.id) {
            // Cross-tenant probe — identical response to "does
            // not exist" so existence does not leak.
            throw EntityNotFoundException("patient not found: ${command.patientId}")
        }

        return toSnapshot(patient)
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
