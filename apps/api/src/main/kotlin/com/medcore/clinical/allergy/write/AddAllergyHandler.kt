package com.medcore.clinical.allergy.write

import com.medcore.clinical.allergy.model.AllergyStatus
import com.medcore.clinical.allergy.persistence.AllergyEntity
import com.medcore.clinical.allergy.persistence.AllergyRepository
import com.medcore.clinical.encounter.persistence.EncounterRepository
import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * Handler for [AddAllergyCommand] (Phase 4E.1).
 *
 * Runs inside the [com.medcore.platform.write.WriteGate]-owned
 * transaction after [AddAllergyPolicy] has approved
 * `ALLERGY_WRITE`. `PhiRlsTxHook` has set both RLS GUCs by the
 * time this handler runs.
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug (invariant — policy already checked
 *    ACTIVE membership).
 * 2. Load patient by id; verify `patient.tenantId == tenant.id`.
 *    Cross-tenant probe → 404 indistinguishable from "unknown
 *    patient" — no existence leak. Mirrors the 4A.4
 *    `GetPatientHandler` belt-and-braces discipline.
 * 3. If `recordedInEncounterId` is provided, verify the
 *    encounter exists, belongs to the same tenant, AND belongs
 *    to the same patient. Cross-tenant / cross-patient → 404
 *    on the encounter id (still no existence leak; the
 *    response is identical to "unknown encounter").
 * 4. INSERT a new allergy row with `status = ACTIVE`,
 *    `tenant_id` and `patient_id` from the verified parents.
 *    `saveAndFlush` so `row_version` is populated before the
 *    auditor runs.
 * 5. Return [AllergySnapshot].
 *
 * ### No-op suppression
 *
 * Add is never a no-op — every call mints a new UUID and a new
 * row. Sibling allergies (same substance text, same patient,
 * different rows) are intentionally allowed in 4E.1 per the
 * locked Q5: free-text dedup is unreliable, coded-substance
 * dedup arrives with 5A FHIR. Clinicians who genuinely double-
 * record can mark one ENTERED_IN_ERROR via PATCH.
 */
@Component
class AddAllergyHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
    private val allergyRepository: AllergyRepository,
    private val clock: Clock,
) {

    fun handle(
        command: AddAllergyCommand,
        context: WriteContext,
    ): AllergySnapshot {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val patient = patientRepository.findById(command.patientId).orElse(null)
            ?: throw EntityNotFoundException("patient not found: ${command.patientId}")
        if (patient.tenantId != tenant.id) {
            throw EntityNotFoundException("patient not found: ${command.patientId}")
        }

        val encounterId = command.recordedInEncounterId
        if (encounterId != null) {
            val encounter = encounterRepository.findById(encounterId).orElse(null)
                ?: throw EntityNotFoundException("encounter not found: $encounterId")
            if (encounter.tenantId != tenant.id || encounter.patientId != patient.id) {
                throw EntityNotFoundException("encounter not found: $encounterId")
            }
        }

        val now = Instant.now(clock)
        val entity = AllergyEntity(
            id = UUID.randomUUID(),
            tenantId = tenant.id,
            patientId = patient.id,
            substanceText = command.substanceText,
            substanceCode = null,
            substanceSystem = null,
            severity = command.severity,
            status = AllergyStatus.ACTIVE,
            reactionText = command.reactionText,
            onsetDate = command.onsetDate,
            recordedInEncounterId = encounterId,
            createdAt = now,
            updatedAt = now,
            createdBy = context.principal.userId,
            updatedBy = context.principal.userId,
        )
        val saved = allergyRepository.saveAndFlush(entity)
        return AllergySnapshot.from(saved)
    }
}
