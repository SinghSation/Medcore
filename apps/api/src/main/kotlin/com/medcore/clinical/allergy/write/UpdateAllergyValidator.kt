package com.medcore.clinical.allergy.write

import com.medcore.clinical.patient.write.Patchable
import com.medcore.platform.write.WriteValidationException
import com.medcore.platform.write.WriteValidator
import org.springframework.stereotype.Component

/**
 * Domain validation for [UpdateAllergyCommand] (Phase 4E.1).
 *
 * Pure (no DB calls). Cross-row checks (allergy belongs to
 * tenant + patient, status transition validity, terminal
 * state refusal) all live in the handler.
 *
 * Enforces:
 *   - At least one field is being patched (otherwise the call
 *     is meaningless — no-op detection in the handler covers
 *     the "all-equal-to-existing" case, but a totally-empty
 *     patch is a client bug worth flagging at 422).
 *   - `severity` and `status` may not be `Clear` — both are
 *     non-null DB columns. Validator surface for these is the
 *     "you sent JSON null on a required field" 422 path.
 *   - `reactionText` may be `Clear` (column is nullable) and,
 *     when `Set`, ≤ 4000 chars (matches V24 CHECK constraint
 *     `ck_clinical_allergy_reaction_text_length`).
 *   - `onsetDate` may be `Clear` (column is nullable). No
 *     length check (DATE).
 */
@Component
class UpdateAllergyValidator : WriteValidator<UpdateAllergyCommand> {

    override fun validate(command: UpdateAllergyCommand) {
        if (command.slug.isBlank()) {
            throw WriteValidationException(field = "slug", code = "required")
        }

        // Empty-patch guard.
        val isEmpty = command.severity is Patchable.Absent &&
            command.reactionText is Patchable.Absent &&
            command.onsetDate is Patchable.Absent &&
            command.status is Patchable.Absent
        if (isEmpty) {
            throw WriteValidationException(field = "body", code = "required")
        }

        // severity: non-null column → Clear is illegal.
        if (command.severity is Patchable.Clear) {
            throw WriteValidationException(field = "severity", code = "required")
        }

        // status: non-null column → Clear is illegal.
        if (command.status is Patchable.Clear) {
            throw WriteValidationException(field = "status", code = "required")
        }

        // reactionText: nullable column; Clear is allowed.
        // When Set, length-bounded.
        val reaction = command.reactionText
        if (reaction is Patchable.Set && reaction.value.length > MAX_REACTION_LENGTH) {
            throw WriteValidationException(field = "reactionText", code = "max_length")
        }
    }

    private companion object {
        const val MAX_REACTION_LENGTH: Int = 4000
    }
}
