package com.medcore.clinical.allergy.write

import com.medcore.platform.write.WriteValidationException
import com.medcore.platform.write.WriteValidator
import org.springframework.stereotype.Component

/**
 * Domain validation for [AddAllergyCommand] (Phase 4E.1).
 *
 * Mirrors the V24 CHECK constraints at the application layer
 * for clean 422 surfaces (instead of raw DB constraint
 * violations turning into 409s):
 *   - `substance_text` non-blank after trim, ≤ 500 chars
 *   - `reaction_text` ≤ 4000 chars (if provided; null is fine)
 *
 * Severity is already type-safe (closed enum at the Kotlin
 * layer) so no validator gate needed for it.
 *
 * `onset_date` is bounded by `LocalDate` itself — no extra
 * validation in 4E.1. A future "onset cannot be in the future"
 * check is a clinical-policy slice (most patients can't have
 * an allergy onset tomorrow, but a "scheduled exposure
 * monitoring" workflow could legitimately want it).
 *
 * `recordedInEncounterId` is verified by the handler (encounter
 * exists + belongs to same tenant + same patient) rather than
 * the validator — the validator is pure (no DB calls); FK-
 * style cross-row checks belong in the handler.
 */
@Component
class AddAllergyValidator : WriteValidator<AddAllergyCommand> {

    override fun validate(command: AddAllergyCommand) {
        if (command.slug.isBlank()) {
            throw WriteValidationException(field = "slug", code = "required")
        }
        val trimmedSubstance = command.substanceText.trim()
        if (trimmedSubstance.isEmpty()) {
            throw WriteValidationException(field = "substanceText", code = "required")
        }
        if (command.substanceText.length > MAX_SUBSTANCE_LENGTH) {
            throw WriteValidationException(field = "substanceText", code = "max_length")
        }
        val reaction = command.reactionText
        if (reaction != null && reaction.length > MAX_REACTION_LENGTH) {
            throw WriteValidationException(field = "reactionText", code = "max_length")
        }
    }

    private companion object {
        const val MAX_SUBSTANCE_LENGTH: Int = 500
        const val MAX_REACTION_LENGTH: Int = 4000
    }
}
