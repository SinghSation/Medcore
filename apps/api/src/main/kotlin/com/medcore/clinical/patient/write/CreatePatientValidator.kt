package com.medcore.clinical.patient.write

import com.medcore.platform.write.WriteValidationException
import com.medcore.platform.write.WriteValidator
import java.time.Clock
import java.time.LocalDate
import org.springframework.stereotype.Component

/**
 * Domain validator for [CreatePatientCommand] (Phase 4A.2).
 *
 * Bean Validation on [com.medcore.clinical.patient.api.CreatePatientRequest]
 * covers @NotBlank / @Size shape checks at the HTTP boundary.
 * This validator handles the domain checks Bean Validation cannot
 * express cleanly:
 *
 * 1. **Tenant slug format** — must match the Medcore slug regex
 *    (same as tenancy). Defence-in-depth for future internal
 *    callers that bypass the DTO.
 * 2. **Post-trim emptiness** on every name part — `@NotBlank`
 *    accepts ` ` (one space). We reject trimmed-empty values.
 * 3. **Control-character rejection** on name / language /
 *    gender-identity fields — tabs, newlines, NUL bytes should
 *    not appear in PHI text.
 * 4. **Birth-date sanity** — must not be in the future and not
 *    absurdly far in the past (no patient born before 1900;
 *    tighten later if age-related clinical logic demands it).
 * 5. **Length caps** — defensive upper bounds on every TEXT
 *    field (matches the realistic upper bound for HumanName
 *    parts in FHIR data) so an adversarial payload cannot bloat
 *    the row.
 *
 * ### Leakage discipline
 *
 * The validator throws [WriteValidationException] with field
 * name + closed-enum code. Never includes the rejected VALUE
 * (which may be PHI). Phase 3G's handler maps to 422 with
 * `details.validationErrors = [{field, code}]` — field names
 * + code slugs only.
 *
 * ### Codes emitted (closed set)
 *
 *  - `blank` — field is empty or whitespace-only after trim.
 *  - `format` — slug pattern violation.
 *  - `too_long` — value exceeds the cap for that field.
 *  - `control_chars` — value contains control characters.
 *  - `in_future` — birth_date is > today (UTC).
 *  - `too_old` — birth_date is before 1900-01-01.
 */
@Component
class CreatePatientValidator(
    private val clock: Clock,
) : WriteValidator<CreatePatientCommand> {

    override fun validate(command: CreatePatientCommand) {
        // --- slug ---
        if (command.slug.isBlank()) {
            throw WriteValidationException(field = "slug", code = "blank")
        }
        if (!SLUG_PATTERN.matches(command.slug)) {
            throw WriteValidationException(field = "slug", code = "format")
        }

        // --- required name parts ---
        validateNonBlank(command.nameGiven, "nameGiven")
        validateNonBlank(command.nameFamily, "nameFamily")

        // --- field-cap + control-char checks ---
        validateTextField(command.nameGiven, "nameGiven", NAME_PART_MAX)
        validateTextField(command.nameFamily, "nameFamily", NAME_PART_MAX)
        command.nameMiddle?.let { validateTextField(it, "nameMiddle", NAME_PART_MAX) }
        command.nameSuffix?.let { validateTextField(it, "nameSuffix", NAME_PART_MAX) }
        command.namePrefix?.let { validateTextField(it, "namePrefix", NAME_PART_MAX) }
        command.preferredName?.let { validateTextField(it, "preferredName", NAME_PART_MAX) }
        command.sexAssignedAtBirth?.let { validateTextField(it, "sexAssignedAtBirth", CODE_MAX) }
        command.genderIdentityCode?.let { validateTextField(it, "genderIdentityCode", CODE_MAX) }
        command.preferredLanguage?.let { validateTextField(it, "preferredLanguage", LANGUAGE_MAX) }

        // --- birth date ---
        val today = LocalDate.now(clock)
        if (command.birthDate.isAfter(today)) {
            throw WriteValidationException(field = "birthDate", code = "in_future")
        }
        if (command.birthDate.isBefore(MIN_BIRTH_DATE)) {
            throw WriteValidationException(field = "birthDate", code = "too_old")
        }
    }

    private fun validateNonBlank(value: String, field: String) {
        if (value.trim().isEmpty()) {
            throw WriteValidationException(field = field, code = "blank")
        }
    }

    private fun validateTextField(value: String, field: String, maxLength: Int) {
        if (value.length > maxLength) {
            throw WriteValidationException(field = field, code = "too_long")
        }
        if (CONTROL_CHARS.containsMatchIn(value)) {
            throw WriteValidationException(field = field, code = "control_chars")
        }
    }

    private companion object {
        val SLUG_PATTERN: Regex = Regex("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$")

        // Realistic upper bounds. HumanName parts rarely exceed 120
        // chars in any FHIR sample dataset; we cap at 200 for a
        // defensive margin. Codes (administrative, gender identity)
        // are SNOMED / HL7 short strings.
        const val NAME_PART_MAX: Int = 200
        const val CODE_MAX: Int = 64
        const val LANGUAGE_MAX: Int = 35 // BCP 47 language tags

        // Tab, NEL, and C0/C1 control ranges (excluding \r\n which
        // we also reject — no newlines in single-line PHI fields).
        val CONTROL_CHARS: Regex = Regex("[\\x00-\\x1F\\x7F-\\x9F]")

        val MIN_BIRTH_DATE: LocalDate = LocalDate.of(1900, 1, 1)
    }
}
