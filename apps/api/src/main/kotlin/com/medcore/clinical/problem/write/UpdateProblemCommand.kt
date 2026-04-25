package com.medcore.clinical.problem.write

import com.medcore.clinical.patient.write.Patchable
import com.medcore.clinical.problem.model.ProblemSeverity
import com.medcore.clinical.problem.model.ProblemStatus
import java.time.LocalDate
import java.util.UUID

/**
 * Command for
 * `PATCH /api/v1/tenants/{slug}/patients/{patientId}/problems/{problemId}`
 * (Phase 4E.2).
 *
 * Partial-update over the four mutable problem fields:
 *   - `severity` (closed enum, NULLABLE) — clinical refinement;
 *     `Patchable.Clear` IS legal (column is nullable, distinct
 *     from allergy where severity is required).
 *   - `onsetDate` (date or null) — clinical refinement.
 *   - `abatementDate` (date or null) — clinical refinement.
 *   - `status` — lifecycle transition.
 *
 * `conditionText` is intentionally NOT in the patch surface
 * per locked Q7 (immutable post-create). To "change" the
 * condition, the user marks the row ENTERED_IN_ERROR via this
 * PATCH and creates a new problem via POST.
 *
 * ### Status transitions (handler-enforced — locked Q8)
 *
 * Routed by **target status** to one of three audit actions:
 *
 *   - `ACTIVE ↔ INACTIVE` (chronic flare/quiesce) → emits
 *     `CLINICAL_PROBLEM_UPDATED` with
 *     `status_from`/`status_to`.
 *   - `RESOLVED → ACTIVE` (recurrence) → emits
 *     `CLINICAL_PROBLEM_UPDATED` with
 *     `status_from:RESOLVED|status_to:ACTIVE`. Forensic
 *     narrowing on recurrence happens via the `status_from`
 *     token, not via a distinct action.
 *   - `ACTIVE → RESOLVED` or `INACTIVE → RESOLVED` → emits
 *     `CLINICAL_PROBLEM_RESOLVED` with `prior_status:<X>`.
 *     Distinct action because clinical-outcome reporting
 *     ("how many problems resolved last quarter?") is a
 *     high-value query that MUST NOT collide with INACTIVE
 *     transitions.
 *   - any → `ENTERED_IN_ERROR` → emits
 *     `CLINICAL_PROBLEM_REVOKED` with `prior_status:<X>`.
 *   - `RESOLVED → INACTIVE` is REFUSED → 409
 *     `details.reason: problem_invalid_transition`.
 *     RESOLVED is a stronger statement than INACTIVE;
 *     "downgrading" requires the clinician to first
 *     transition through ACTIVE (recurrence) or revoke the
 *     resolution as ENTERED_IN_ERROR.
 *   - `ENTERED_IN_ERROR → anything` is REFUSED → 409
 *     `details.reason: problem_terminal`. Same discipline as
 *     `allergy_terminal`.
 *
 * **RESOLVED ≠ INACTIVE** (see [ProblemStatus] KDoc) — the
 * three-action audit dispatch and the explicit RESOLVED →
 * INACTIVE refusal both encode this distinction.
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
 *   - `Clear` — caller sent JSON null; write NULL. Legal on
 *     `severity`, `onsetDate`, `abatementDate` (nullable
 *     columns); refused for `status` (the validator rejects
 *     it with `code = required`).
 */
data class UpdateProblemCommand(
    val slug: String,
    val patientId: UUID,
    val problemId: UUID,
    val expectedRowVersion: Long,
    val severity: Patchable<ProblemSeverity> = Patchable.Absent,
    val onsetDate: Patchable<LocalDate> = Patchable.Absent,
    val abatementDate: Patchable<LocalDate> = Patchable.Absent,
    val status: Patchable<ProblemStatus> = Patchable.Absent,
)
// `changingFieldNames()` deliberately omitted: unlike
// UpdatePatientDemographicsCommand / UpdateAllergyCommand which
// expose a top-level "fields requested for change" set, the
// problem update flow tracks ACTUALLY-changed fields inside
// UpdateProblemHandler (the per-field apply*() helpers return
// the field name only when the value really moved). The auditor
// reads `result.changedFields` from the handler outcome — never
// the command's intended set — so a "user PATCHed severity:MILD
// to MILD" no-op never appears in the audit slug. A method that
// returned "intended-to-change" fields would be dead code.
