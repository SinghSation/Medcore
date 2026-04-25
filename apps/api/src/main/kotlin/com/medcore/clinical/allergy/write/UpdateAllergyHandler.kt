package com.medcore.clinical.allergy.write

import com.medcore.clinical.allergy.model.AllergySeverity
import com.medcore.clinical.allergy.model.AllergyStatus
import com.medcore.clinical.allergy.persistence.AllergyEntity
import com.medcore.clinical.allergy.persistence.AllergyRepository
import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.clinical.patient.write.Patchable
import com.medcore.platform.write.WriteConflictException
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import org.springframework.stereotype.Component

/**
 * Handler for [UpdateAllergyCommand] (Phase 4E.1).
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug (invariant — policy approved).
 * 2. Load the patient; verify `patient.tenantId == tenant.id`
 *    (cross-tenant probe → 404 — no existence leak).
 * 3. Load the allergy; verify `allergy.tenantId == tenant.id`
 *    AND `allergy.patientId == patient.id` (either mismatch
 *    → 404, identical to "unknown allergy").
 * 4. Compare `expectedRowVersion` to the loaded row's
 *    `row_version`. Mismatch → [WriteConflictException]
 *    `stale_row` (mirrors `UpdatePatientDemographicsHandler`
 *    discipline: `If-Match` precondition surfaces explicitly,
 *    JPA `@Version` is the defense-in-depth at flush).
 * 5. Status-transition gate (NORMATIVE — locked Q1):
 *    - If current status is `ENTERED_IN_ERROR` AND any patch
 *      would actually change anything → 409
 *      `details.reason: allergy_terminal`.
 *    - If current status is `ENTERED_IN_ERROR` AND every patch
 *      resolves to no change → return `changed = false`
 *      (idempotent retry on a terminal row; suppressed audit).
 * 6. Apply patches via [Patchable.Absent] / `Set` / `Clear`
 *    semantics. No-op detection inside `apply()` — equal-value
 *    assignments do not mark the entity changed.
 * 7. If nothing actually changed, return early with
 *    `changed = false`. The auditor suppresses emission.
 * 8. Otherwise stamp `updated_at` + `updated_by` and
 *    `saveAndFlush`. JPA `@Version` bumps `row_version`.
 * 9. Return [UpdateAllergyOutcome] carrying the dispatch flag
 *    so the auditor knows whether to emit
 *    `CLINICAL_ALLERGY_UPDATED` (refinement / ACTIVE↔INACTIVE)
 *    or `CLINICAL_ALLERGY_REVOKED` (target status =
 *    ENTERED_IN_ERROR).
 *
 * ### Why the auditor dispatch lives in the handler outcome
 *
 * The wire surface is one PATCH endpoint, but the audit
 * surface is two distinct actions (UPDATED vs REVOKED) per
 * the locked plan. Putting the dispatch flag on the handler
 * outcome means the auditor stays a thin event-shape mapper
 * — no business logic in the auditor about "was this a
 * revoke?".
 */
@Component
class UpdateAllergyHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
    private val allergyRepository: AllergyRepository,
    private val clock: Clock,
) {

    fun handle(
        command: UpdateAllergyCommand,
        context: WriteContext,
    ): UpdateAllergyOutcome {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val patient = patientRepository.findById(command.patientId).orElse(null)
            ?: throw EntityNotFoundException("patient not found: ${command.patientId}")
        if (patient.tenantId != tenant.id) {
            throw EntityNotFoundException("patient not found: ${command.patientId}")
        }

        val allergy = allergyRepository.findById(command.allergyId).orElse(null)
            ?: throw EntityNotFoundException("allergy not found: ${command.allergyId}")
        if (allergy.tenantId != tenant.id || allergy.patientId != patient.id) {
            // Cross-tenant or cross-patient probe → 404 identical
            // to "unknown allergy"; no existence leak.
            throw EntityNotFoundException("allergy not found: ${command.allergyId}")
        }

        if (allergy.rowVersion != command.expectedRowVersion) {
            throw WriteConflictException("stale_row")
        }

        val priorStatus = allergy.status

        // Snapshot the changes BEFORE mutating so we can compute
        // changedFields and the from→to status transition for
        // the audit slug. apply() returns true if it actually
        // mutated the entity (no-op suppression at field level).
        val changedFields = mutableSetOf<String>()

        applySeverity(allergy, command.severity)?.also { changedFields += it }
        applyReactionText(allergy, command.reactionText)?.also { changedFields += it }
        applyOnsetDate(allergy, command.onsetDate)?.also { changedFields += it }
        applyStatus(allergy, command.status)?.also { changedFields += it }

        val changed = changedFields.isNotEmpty()
        val newStatus = allergy.status

        // Terminal-state guard: if the row was ENTERED_IN_ERROR
        // before this call AND the caller is trying to change
        // anything, refuse. An idempotent re-revoke (current ==
        // ENTERED_IN_ERROR, all patches resolve to no-op) falls
        // through as `changed = false` and is suppressed.
        if (priorStatus == AllergyStatus.ENTERED_IN_ERROR && changed) {
            throw WriteConflictException("allergy_terminal")
        }

        if (!changed) {
            return UpdateAllergyOutcome(
                snapshot = AllergySnapshot.from(allergy),
                changed = false,
                changedFields = emptySet(),
                priorStatus = priorStatus,
                wasRevoke = false,
            )
        }

        allergy.updatedAt = Instant.now(clock)
        allergy.updatedBy = context.principal.userId
        val saved = allergyRepository.saveAndFlush(allergy)

        return UpdateAllergyOutcome(
            snapshot = AllergySnapshot.from(saved),
            changed = true,
            changedFields = changedFields.toSet(),
            priorStatus = priorStatus,
            wasRevoke = newStatus == AllergyStatus.ENTERED_IN_ERROR,
        )
    }

    /**
     * Returns the field name if a change was applied; null on
     * no-op.
     */
    private fun applySeverity(
        entity: AllergyEntity,
        patch: Patchable<AllergySeverity>,
    ): String? = when (patch) {
        Patchable.Absent -> null
        Patchable.Clear -> null // refused by validator
        is Patchable.Set -> if (patch.value != entity.severity) {
            entity.severity = patch.value
            "severity"
        } else null
    }

    private fun applyReactionText(
        entity: AllergyEntity,
        patch: Patchable<String>,
    ): String? = when (patch) {
        Patchable.Absent -> null
        Patchable.Clear -> if (entity.reactionText != null) {
            entity.reactionText = null
            "reactionText"
        } else null
        is Patchable.Set -> if (patch.value != entity.reactionText) {
            entity.reactionText = patch.value
            "reactionText"
        } else null
    }

    private fun applyOnsetDate(
        entity: AllergyEntity,
        patch: Patchable<LocalDate>,
    ): String? = when (patch) {
        Patchable.Absent -> null
        Patchable.Clear -> if (entity.onsetDate != null) {
            entity.onsetDate = null
            "onsetDate"
        } else null
        is Patchable.Set -> if (patch.value != entity.onsetDate) {
            entity.onsetDate = patch.value
            "onsetDate"
        } else null
    }

    private fun applyStatus(
        entity: AllergyEntity,
        patch: Patchable<AllergyStatus>,
    ): String? = when (patch) {
        Patchable.Absent -> null
        Patchable.Clear -> null // refused by validator
        is Patchable.Set -> if (patch.value != entity.status) {
            entity.status = patch.value
            "status"
        } else null
    }
}

/**
 * Handler outcome for allergy updates — carries the snapshot
 * for the response body, the no-op flag for audit suppression,
 * the changed-field set + prior-status for the audit reason
 * slug, and the dispatch flag the auditor uses to decide
 * between `CLINICAL_ALLERGY_UPDATED` and
 * `CLINICAL_ALLERGY_REVOKED`.
 */
data class UpdateAllergyOutcome(
    val snapshot: AllergySnapshot,
    val changed: Boolean,
    val changedFields: Set<String>,
    val priorStatus: AllergyStatus,
    val wasRevoke: Boolean,
)
