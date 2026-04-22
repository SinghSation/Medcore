package com.medcore.tenancy.write

import com.medcore.platform.write.WriteValidationException
import com.medcore.platform.write.WriteValidator
import org.springframework.stereotype.Component

/**
 * Domain validator for [UpdateTenantDisplayNameCommand] (Phase 3J.2).
 *
 * Runs BEFORE authorization and BEFORE any transaction opens. Any
 * rejection throws [WriteValidationException] — the Phase 3G
 * `GlobalExceptionHandler` maps that to `422
 * request.validation_failed` with `details.validationErrors =
 * [{field, code}]`, same shape the HTTP `@Valid` path produces.
 *
 * ### Why not rely on `@Valid` alone?
 *
 * The controller already runs `@Valid @RequestBody
 * UpdateTenantRequest`, which enforces `@NotBlank` / `@Size(max =
 * 200)` on the wire shape. That catches:
 *   - Missing `displayName` field (422 code `NotBlank`)
 *   - Empty-string `displayName` (422 code `NotBlank`)
 *   - Overlong `displayName` (422 code `Size`)
 *
 * Bean Validation cannot express the remaining rules without a
 * custom annotation, and those rules only matter once the command
 * crosses into the domain:
 *   - Whitespace-only strings survive `@NotBlank` technically-true
 *     — the validator rejects `displayName.trim().isEmpty()`.
 *   - Control characters (newlines, tabs, NULs) pass `@Size` but
 *     break audit-log slugs and terminal displays — rejected.
 *   - Slug format sanity check (defence-in-depth; the
 *     `@PathVariable` does not constrain the URL path).
 */
@Component
class UpdateTenantDisplayNameValidator : WriteValidator<UpdateTenantDisplayNameCommand> {

    override fun validate(command: UpdateTenantDisplayNameCommand) {
        validateSlug(command.slug)
        validateDisplayName(command.displayName)
    }

    private fun validateSlug(slug: String) {
        if (slug.isBlank()) {
            throw WriteValidationException(field = "slug", code = "blank")
        }
        if (!SLUG_PATTERN.matches(slug)) {
            throw WriteValidationException(field = "slug", code = "format")
        }
    }

    private fun validateDisplayName(displayName: String) {
        if (displayName.isBlank()) {
            throw WriteValidationException(field = "displayName", code = "blank")
        }
        if (displayName.length > DISPLAY_NAME_MAX_LENGTH) {
            throw WriteValidationException(field = "displayName", code = "too_long")
        }
        if (displayName.any { Character.isISOControl(it) }) {
            throw WriteValidationException(field = "displayName", code = "control_chars")
        }
    }

    private companion object {
        // Matches V5__tenant.sql's CHECK constraint on slug.
        val SLUG_PATTERN: Regex = Regex("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$")
        const val DISPLAY_NAME_MAX_LENGTH: Int = 200
    }
}
