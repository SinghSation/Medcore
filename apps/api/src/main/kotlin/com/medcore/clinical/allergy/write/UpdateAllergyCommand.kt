package com.medcore.clinical.allergy.write

import com.medcore.clinical.allergy.model.AllergySeverity
import com.medcore.clinical.allergy.model.AllergyStatus
import com.medcore.clinical.patient.write.Patchable
import java.time.LocalDate
import java.util.UUID

/**
 * Command for
 * `PATCH /api/v1/tenants/{slug}/patients/{patientId}/allergies/{allergyId}`
 * (Phase 4E.1).
 *
 * Partial-update over the four mutable allergy fields:
 *   - `severity` (closed enum) — clinical refinement
 *   - `reactionText` (free text or null) — clinical refinement
 *   - `onsetDate` (date or null) — clinical refinement
 *   - `status` — lifecycle transition
 *
 * `substanceText` is intentionally NOT in the patch surface
 * per locked Q2 (immutable post-create). To "change" the
 * substance, the user marks the row ENTERED_IN_ERROR via this
 * PATCH and creates a new allergy via POST.
 *
 * ### Status transitions (handler-enforced per locked Q1)
 *
 *   - ACTIVE ↔ INACTIVE: bidirectional → emits
 *     `CLINICAL_ALLERGY_UPDATED` with `status_from`/`status_to`
 *   - any → ENTERED_IN_ERROR: terminal retraction → emits
 *     `CLINICAL_ALLERGY_REVOKED` (auditor dispatches based on
 *     the post-state)
 *   - ENTERED_IN_ERROR → anything: refused → 409
 *     `details.reason: allergy_terminal`
 *
 * ### Optimistic concurrency
 *
 * `expectedRowVersion` — caller-supplied If-Match precondition.
 * Mismatch with the loaded row's `row_version` → 409
 * `details.reason: stale_row`. JPA `@Version` is the
 * defense-in-depth at flush; the explicit handler check
 * surfaces a deterministic reason slug.
 *
 * ### Patchable semantics
 *
 * Reuses `com.medcore.clinical.patient.write.Patchable` (Phase
 * 4A.2 substrate). Three states per field:
 *   - `Absent` — caller did not send the field; column unchanged
 *   - `Set(value)` — caller sent a non-null value; write it
 *   - `Clear` — caller sent JSON null; write NULL (legal only on
 *     nullable columns; refused for `severity` and `status` by
 *     the validator with `code = required`)
 */
data class UpdateAllergyCommand(
    val slug: String,
    val patientId: UUID,
    val allergyId: UUID,
    val expectedRowVersion: Long,
    val severity: Patchable<AllergySeverity> = Patchable.Absent,
    val reactionText: Patchable<String> = Patchable.Absent,
    val onsetDate: Patchable<LocalDate> = Patchable.Absent,
    val status: Patchable<AllergyStatus> = Patchable.Absent,
) {
    /**
     * Closed set of camelCase field names the caller is
     * attempting to change. Used by the auditor to populate the
     * `fields:<csv>` reason slug. Order matches declaration so
     * forensic consumers can rely on it (alphabetisation would
     * obscure intent).
     */
    fun changingFieldNames(): Set<String> = buildSet {
        if (severity !is Patchable.Absent) add("severity")
        if (reactionText !is Patchable.Absent) add("reactionText")
        if (onsetDate !is Patchable.Absent) add("onsetDate")
        if (status !is Patchable.Absent) add("status")
    }
}
