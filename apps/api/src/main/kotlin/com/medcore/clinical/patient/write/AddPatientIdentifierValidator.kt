package com.medcore.clinical.patient.write

import com.medcore.platform.write.WriteValidationException
import com.medcore.platform.write.WriteValidator
import org.springframework.stereotype.Component

/**
 * Domain validator for [AddPatientIdentifierCommand] (Phase 4A.3).
 *
 * Follows `clinical-write-pattern.md` §3:
 * `[REQUIRED §3.1]` `@Component` implementing `WriteValidator<CMD>`;
 * runs OUTSIDE the transaction.
 * `[REQUIRED §3.1]` emits `WriteValidationException(field, code)`
 * with closed-enum codes; field+code only, never values (PHI).
 *
 * ### Codes emitted (closed set)
 *
 * Shared vocabulary with 4A.2 validators plus one new code:
 *
 * - `blank` — field is empty or whitespace-only after trim.
 * - `format` — slug pattern violation.
 * - `too_long` — value exceeds the cap for that field.
 * - `control_chars` — value contains control characters.
 * - `valid_range` (new for 4A.3) — `valid_from` > `valid_to`
 *   (e.g., a newly added identifier already valid-expired).
 */
@Component
class AddPatientIdentifierValidator : WriteValidator<AddPatientIdentifierCommand> {

    override fun validate(command: AddPatientIdentifierCommand) {
        // --- slug ---
        if (command.slug.isBlank()) {
            throw WriteValidationException(field = "slug", code = "blank")
        }
        if (!SLUG_PATTERN.matches(command.slug)) {
            throw WriteValidationException(field = "slug", code = "format")
        }

        // --- required non-blank + caps + control-char discipline ---
        validateTextField(command.issuer, "issuer", ISSUER_MAX)
        validateTextField(command.value, "value", VALUE_MAX)

        // --- valid_from/valid_to coherence ---
        if (command.validFrom != null
            && command.validTo != null
            && command.validFrom.isAfter(command.validTo)) {
            throw WriteValidationException(field = "validTo", code = "valid_range")
        }
    }

    private fun validateTextField(value: String, field: String, maxLength: Int) {
        if (value.trim().isEmpty()) {
            throw WriteValidationException(field = field, code = "blank")
        }
        if (value.length > maxLength) {
            throw WriteValidationException(field = field, code = "too_long")
        }
        if (CONTROL_CHARS.containsMatchIn(value)) {
            throw WriteValidationException(field = field, code = "control_chars")
        }
    }

    private companion object {
        val SLUG_PATTERN: Regex = Regex("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$")

        // Realistic caps. Issuer is typically a short payer name or
        // state abbreviation; values are typically license / member
        // numbers or external MRNs. 200/256 are defensive margins.
        const val ISSUER_MAX: Int = 200
        const val VALUE_MAX: Int = 256

        val CONTROL_CHARS: Regex = Regex("[\\x00-\\x1F\\x7F-\\x9F]")
    }
}
