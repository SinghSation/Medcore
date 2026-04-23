package com.medcore.clinical.patient.write

import com.medcore.platform.write.WriteValidationException
import com.medcore.platform.write.WriteValidator
import java.time.Clock
import java.time.LocalDate
import org.springframework.stereotype.Component

/**
 * Domain validator for [UpdatePatientDemographicsCommand]
 * (Phase 4A.2).
 *
 * Checks:
 *
 * 1. **Slug format** — defence-in-depth for internal callers
 *    bypassing the DTO.
 * 2. **Row-version sanity** — `expectedRowVersion` must be >= 0.
 *    Clients receive `rowVersion` as a non-negative integer on
 *    POST/PATCH responses; a negative value is a client bug.
 * 3. **Per-patchable-field rules** — for each field:
 *    - [Patchable.Set] — shape check (non-blank, length cap, no
 *      control chars, legal date range).
 *    - [Patchable.Clear] — refused on required columns
 *      (nameGiven, nameFamily, birthDate, administrativeSex)
 *      because clearing them would violate NOT NULL at the DB
 *      layer. Return error code `required` — same shape as
 *      bean-validation `@NotNull`.
 *    - [Patchable.Absent] — no-op.
 *
 * ### Emit codes (closed set)
 *
 * Shared with [CreatePatientValidator] plus:
 *
 *   - `required` — caller sent null for a NOT-NULL column.
 *   - `negative` — `expectedRowVersion` < 0.
 *   - `no_fields` — zero patchable fields present in the command.
 *     PATCH with an empty body is a client error (what's the
 *     intent?). Returns 422 instead of silently no-op'ing.
 */
@Component
class UpdatePatientDemographicsValidator(
    private val clock: Clock,
) : WriteValidator<UpdatePatientDemographicsCommand> {

    override fun validate(command: UpdatePatientDemographicsCommand) {
        if (command.slug.isBlank()) {
            throw WriteValidationException(field = "slug", code = "blank")
        }
        if (!SLUG_PATTERN.matches(command.slug)) {
            throw WriteValidationException(field = "slug", code = "format")
        }
        if (command.expectedRowVersion < 0) {
            throw WriteValidationException(field = "expectedRowVersion", code = "negative")
        }
        if (command.changingFieldNames().isEmpty()) {
            throw WriteValidationException(field = "body", code = "no_fields")
        }

        // Required columns — Clear is refused.
        refuseClear(command.nameGiven, "nameGiven")
        refuseClear(command.nameFamily, "nameFamily")
        refuseClear(command.birthDate, "birthDate")
        refuseClear(command.administrativeSex, "administrativeSex")

        // Name parts — validate Set values (Clear ignored per above
        // refusal; Absent does nothing).
        validateTextPatchable(command.nameGiven, "nameGiven", NAME_PART_MAX)
        validateTextPatchable(command.nameFamily, "nameFamily", NAME_PART_MAX)
        validateTextPatchable(command.nameMiddle, "nameMiddle", NAME_PART_MAX)
        validateTextPatchable(command.nameSuffix, "nameSuffix", NAME_PART_MAX)
        validateTextPatchable(command.namePrefix, "namePrefix", NAME_PART_MAX)
        validateTextPatchable(command.preferredName, "preferredName", NAME_PART_MAX)
        validateTextPatchable(command.sexAssignedAtBirth, "sexAssignedAtBirth", CODE_MAX)
        validateTextPatchable(command.genderIdentityCode, "genderIdentityCode", CODE_MAX)
        validateTextPatchable(command.preferredLanguage, "preferredLanguage", LANGUAGE_MAX)

        if (command.birthDate is Patchable.Set<LocalDate>) {
            val today = LocalDate.now(clock)
            if (command.birthDate.value.isAfter(today)) {
                throw WriteValidationException(field = "birthDate", code = "in_future")
            }
            if (command.birthDate.value.isBefore(MIN_BIRTH_DATE)) {
                throw WriteValidationException(field = "birthDate", code = "too_old")
            }
        }
    }

    private fun refuseClear(patchable: Patchable<*>, field: String) {
        if (patchable is Patchable.Clear) {
            throw WriteValidationException(field = field, code = "required")
        }
    }

    private fun validateTextPatchable(
        patchable: Patchable<String>,
        field: String,
        maxLength: Int,
    ) {
        if (patchable is Patchable.Set<String>) {
            val value = patchable.value
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
    }

    private companion object {
        val SLUG_PATTERN: Regex = Regex("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$")
        const val NAME_PART_MAX: Int = 200
        const val CODE_MAX: Int = 64
        const val LANGUAGE_MAX: Int = 35
        val CONTROL_CHARS: Regex = Regex("[\\x00-\\x1F\\x7F-\\x9F]")
        val MIN_BIRTH_DATE: LocalDate = LocalDate.of(1900, 1, 1)
    }
}
