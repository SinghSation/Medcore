package com.medcore.clinical.problem.write

import com.medcore.platform.write.WriteValidationException
import com.medcore.platform.write.WriteValidator
import org.springframework.stereotype.Component

/**
 * Domain validation for [AddProblemCommand] (Phase 4E.2).
 *
 * Mirrors the V25 CHECK constraints at the application layer
 * for clean 422 surfaces (instead of raw DB constraint
 * violations turning into 409s):
 *
 *   - `condition_text` non-blank after `btrim`, ≤ 500 chars
 *     (matches `ck_clinical_problem_condition_text_length`).
 *   - `abatement_date >= onset_date` when both are present
 *     (matches `ck_clinical_problem_abatement_after_onset`).
 *
 * Severity is type-safe (closed enum) and nullable per locked
 * Q3, so no validator gate is needed for it. `null` is the
 * "unspecified" value — there is no UNKNOWN sentinel.
 *
 * `onsetDate` and `abatementDate` are bounded by `LocalDate`
 * itself — no extra validation in 4E.2. A future "dates cannot
 * be in the future" check is a clinical-policy slice (most
 * problems are observed historically, but a "scheduled
 * surgical recovery" workflow could legitimately want a future
 * onset).
 *
 * `recordedInEncounterId` is verified by the handler (encounter
 * exists + belongs to same tenant + same patient) rather than
 * the validator — the validator is pure (no DB calls); FK-
 * style cross-row checks belong in the handler.
 */
@Component
class AddProblemValidator : WriteValidator<AddProblemCommand> {

    override fun validate(command: AddProblemCommand) {
        if (command.slug.isBlank()) {
            throw WriteValidationException(field = "slug", code = "required")
        }
        val trimmedCondition = command.conditionText.trim()
        if (trimmedCondition.isEmpty()) {
            throw WriteValidationException(field = "conditionText", code = "required")
        }
        if (command.conditionText.length > MAX_CONDITION_LENGTH) {
            throw WriteValidationException(field = "conditionText", code = "max_length")
        }
        // Cross-field: abatement >= onset when both present.
        // Mirrors V25's CHECK so the wire surface fails 422 with
        // a deterministic field name, not a 409 from a DB
        // constraint violation. NULL on either side is allowed.
        val onset = command.onsetDate
        val abatement = command.abatementDate
        if (onset != null && abatement != null && abatement.isBefore(onset)) {
            throw WriteValidationException(
                field = "abatementDate",
                code = "before_onset_date",
            )
        }
    }

    private companion object {
        const val MAX_CONDITION_LENGTH: Int = 500
    }
}
