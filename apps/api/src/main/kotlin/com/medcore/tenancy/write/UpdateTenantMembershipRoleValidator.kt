package com.medcore.tenancy.write

import com.medcore.platform.write.WriteValidationException
import com.medcore.platform.write.WriteValidator
import org.springframework.stereotype.Component

/**
 * Domain validator for [UpdateTenantMembershipRoleCommand] (Phase 3J.N).
 *
 * Bean Validation on
 * [com.medcore.tenancy.api.UpdateMembershipRoleRequest] already
 * rejects null `role` at the HTTP boundary — this validator is
 * defence-in-depth slug-format checking for any future internal
 * caller that bypasses the DTO.
 */
@Component
class UpdateTenantMembershipRoleValidator :
    WriteValidator<UpdateTenantMembershipRoleCommand> {

    override fun validate(command: UpdateTenantMembershipRoleCommand) {
        if (command.slug.isBlank()) {
            throw WriteValidationException(field = "slug", code = "blank")
        }
        if (!SLUG_PATTERN.matches(command.slug)) {
            throw WriteValidationException(field = "slug", code = "format")
        }
    }

    private companion object {
        val SLUG_PATTERN: Regex = Regex("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$")
    }
}
