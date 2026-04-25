package com.medcore.clinical.problem.write

import com.medcore.clinical.patient.write.Patchable
import com.medcore.platform.write.WriteValidationException
import com.medcore.platform.write.WriteValidator
import org.springframework.stereotype.Component

/**
 * Domain validation for [UpdateProblemCommand] (Phase 4E.2).
 *
 * Pure (no DB calls). Cross-row checks (problem belongs to
 * tenant + patient, status transition validity, terminal-state
 * refusal, RESOLVED → INACTIVE refusal, abatement-vs-onset
 * coherence on the post-state) all live in the handler.
 *
 * Enforces:
 *   - At least one field is being patched (otherwise the call
 *     is meaningless — the empty-patch case is a 422, not a
 *     no-op).
 *   - `severity` may be `Clear` (column is NULLABLE per locked
 *     Q3). Distinct from
 *     [com.medcore.clinical.allergy.write.UpdateAllergyValidator]
 *     which refuses severity Clear.
 *   - `status` may NOT be `Clear` — non-null DB column.
 *     Validator surface for "you sent JSON null on a required
 *     field" 422.
 *   - `onsetDate` may be `Clear` (nullable column). No length
 *     check (DATE).
 *   - `abatementDate` may be `Clear` (nullable column). The
 *     cross-field abatement >= onset coherence check is in the
 *     handler (it depends on PRE-image state when only one
 *     side is in the patch).
 */
@Component
class UpdateProblemValidator : WriteValidator<UpdateProblemCommand> {

    override fun validate(command: UpdateProblemCommand) {
        if (command.slug.isBlank()) {
            throw WriteValidationException(field = "slug", code = "required")
        }

        // Empty-patch guard.
        val isEmpty = command.severity is Patchable.Absent &&
            command.onsetDate is Patchable.Absent &&
            command.abatementDate is Patchable.Absent &&
            command.status is Patchable.Absent
        if (isEmpty) {
            throw WriteValidationException(field = "body", code = "required")
        }

        // status: non-null column → Clear is illegal.
        if (command.status is Patchable.Clear) {
            throw WriteValidationException(field = "status", code = "required")
        }

        // severity: nullable column; Clear is allowed.
        // Set values are type-safe (closed enum).

        // Pure pre-shape check on the in-patch dates only.
        // Combined with-PRE-image check happens in the handler
        // because the validator can't see the existing row.
        val onset = command.onsetDate
        val abatement = command.abatementDate
        if (onset is Patchable.Set && abatement is Patchable.Set) {
            if (abatement.value.isBefore(onset.value)) {
                throw WriteValidationException(
                    field = "abatementDate",
                    code = "before_onset_date",
                )
            }
        }
    }
}
