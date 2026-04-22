package com.medcore.tenancy.write

import com.medcore.platform.security.AuthorityResolution
import com.medcore.platform.security.AuthorityResolver
import com.medcore.platform.security.MedcoreAuthority
import com.medcore.platform.write.AuthzPolicy
import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import org.springframework.stereotype.Component

/**
 * Authorization policy for [UpdateTenantDisplayNameCommand]
 * (Phase 3J.2).
 *
 * Matches ADR-007 §4.9 role mapping: display-name updates require
 * [MedcoreAuthority.TENANT_UPDATE], which OWNER and ADMIN hold;
 * MEMBER does not. Denial reasons are forwarded from
 * [AuthorityResolver]'s sealed [AuthorityResolution] so the
 * downstream [com.medcore.platform.audit.AuditAction.AUTHZ_WRITE_DENIED]
 * audit row carries the specific code
 * (`denial:not_a_member` | `denial:membership_suspended` |
 * `denial:tenant_suspended` | `denial:insufficient_authority`).
 */
@Component
class UpdateTenantDisplayNamePolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<UpdateTenantDisplayNameCommand> {

    override fun check(command: UpdateTenantDisplayNameCommand, context: WriteContext) {
        when (val resolution = authorityResolver.resolveFor(context.principal.userId, command.slug)) {
            is AuthorityResolution.Denied ->
                throw WriteAuthorizationException(resolution.reason)
            is AuthorityResolution.Granted -> {
                if (MedcoreAuthority.TENANT_UPDATE !in resolution.authorities) {
                    throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
                }
            }
        }
    }
}
