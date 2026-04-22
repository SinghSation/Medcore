package com.medcore.tenancy.write

import com.medcore.platform.security.AuthorityResolution
import com.medcore.platform.security.AuthorityResolver
import com.medcore.platform.security.MedcoreAuthority
import com.medcore.platform.tenancy.MembershipRole
import com.medcore.platform.write.AuthzPolicy
import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import org.springframework.stereotype.Component

/**
 * Authorization policy for [UpdateTenantMembershipRoleCommand]
 * (Phase 3J.N).
 *
 * Runs BEFORE the gate's transaction opens. Two checks here:
 *
 * 1. **Base authority.** Caller must hold
 *    [MedcoreAuthority.MEMBERSHIP_ROLE_UPDATE].
 * 2. **Promotion-to-OWNER escalation guard.** If `newRole ==
 *    OWNER`, caller must also hold
 *    [MedcoreAuthority.TENANT_DELETE].
 *
 * The **target-OWNER guard** (ADMIN cannot modify an OWNER's
 * membership) lives in the handler — it requires reading the
 * target row, and the target is only visible once the gate's
 * transaction hook sets `app.current_user_id`. Handler-thrown
 * `WriteAuthorizationException` is caught by `WriteGate` and
 * routed through `onDenied` (same treatment as policy-thrown
 * denials) — so the audit story is identical regardless of
 * where the denial originates.
 */
@Component
class UpdateTenantMembershipRolePolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<UpdateTenantMembershipRoleCommand> {

    override fun check(command: UpdateTenantMembershipRoleCommand, context: WriteContext) {
        val resolution = authorityResolver.resolveFor(context.principal.userId, command.slug)
        val authorities = when (resolution) {
            is AuthorityResolution.Denied ->
                throw WriteAuthorizationException(resolution.reason)
            is AuthorityResolution.Granted -> resolution.authorities
        }

        if (MedcoreAuthority.MEMBERSHIP_ROLE_UPDATE !in authorities) {
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }

        if (command.newRole == MembershipRole.OWNER
            && MedcoreAuthority.TENANT_DELETE !in authorities
        ) {
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }
    }
}
