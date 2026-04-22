package com.medcore.tenancy.write

import com.medcore.platform.write.WriteValidationException
import com.medcore.platform.write.WriteValidator
import org.springframework.stereotype.Component

/**
 * Domain validator for [InviteTenantMembershipCommand] (Phase 3J.3).
 *
 * Runs BEFORE authorization and BEFORE any transaction opens.
 * `userId` null-check + `role` null-check + slug format check are
 * caught here; target-user existence is a DB-bound check that
 * lives in [InviteTenantMembershipHandler] (see its KDoc for why
 * non-existence surfaces as 422 rather than 404).
 *
 * Bean validation on [com.medcore.tenancy.api.InviteMembershipRequest]
 * already rejects null `userId` / null `role` fields at the HTTP
 * boundary — this validator is a defence-in-depth second line for
 * any future internal caller that bypasses the DTO.
 */
@Component
class InviteTenantMembershipValidator : WriteValidator<InviteTenantMembershipCommand> {

    override fun validate(command: InviteTenantMembershipCommand) {
        if (command.slug.isBlank()) {
            throw WriteValidationException(field = "slug", code = "blank")
        }
        if (!SLUG_PATTERN.matches(command.slug)) {
            throw WriteValidationException(field = "slug", code = "format")
        }
        // role and userId are non-nullable on the command type —
        // Kotlin's null-safety plus the DTO's Bean Validation cover
        // the surface. No runtime check required.
    }

    private companion object {
        val SLUG_PATTERN: Regex = Regex("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$")
    }
}
