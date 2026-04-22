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
 * Authorization policy for [InviteTenantMembershipCommand]
 * (Phase 3J.3).
 *
 * ### Base rule
 *
 * Caller must hold [MedcoreAuthority.MEMBERSHIP_INVITE] in the
 * target tenant. OWNER + ADMIN hold it; MEMBER does not.
 *
 * ### Privilege-escalation guard (ADR-007 §4.9)
 *
 * An ADMIN inviting someone as OWNER would be a privilege
 * escalation — an ADMIN could create a second OWNER account that
 * ADMIN themselves control, effectively bypassing the OWNER-only
 * authorities (TENANT_DELETE) via a trust chain ADMIN alone
 * should not be able to extend.
 *
 * Guard: when `command.role == OWNER`, the caller must also hold
 * [MedcoreAuthority.TENANT_DELETE]. That authority is OWNER-only
 * by the role-map, so this cleanly distinguishes "OWNER invites
 * OWNER" (allowed) from "ADMIN invites OWNER" (forbidden). Using
 * the AUTHORITY marker rather than the role string keeps the
 * check inside the authority model — renaming or re-scoping the
 * ADMIN role doesn't break the guard.
 *
 * Denial reason is [WriteDenialReason.INSUFFICIENT_AUTHORITY] —
 * identical to a MEMBER trying to invite. Forensic reconstruction
 * distinguishes the two via the actor's recorded role in the
 * `tenant_membership` table at the audited timestamp.
 */
@Component
class InviteTenantMembershipPolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<InviteTenantMembershipCommand> {

    override fun check(command: InviteTenantMembershipCommand, context: WriteContext) {
        val resolution = authorityResolver.resolveFor(context.principal.userId, command.slug)
        when (resolution) {
            is AuthorityResolution.Denied ->
                throw WriteAuthorizationException(resolution.reason)
            is AuthorityResolution.Granted -> {
                if (MedcoreAuthority.MEMBERSHIP_INVITE !in resolution.authorities) {
                    throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
                }
                if (command.role == MembershipRole.OWNER
                    && MedcoreAuthority.TENANT_DELETE !in resolution.authorities
                ) {
                    throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
                }
            }
        }
    }
}
