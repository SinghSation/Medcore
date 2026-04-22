package com.medcore.platform.security

import com.medcore.platform.tenancy.MembershipStatus
import com.medcore.platform.tenancy.ResolvedMembership
import com.medcore.platform.tenancy.TenantMembershipLookup
import com.medcore.platform.tenancy.TenantStatus
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * Resolves the [MedcoreAuthority] set a given principal holds
 * within a given tenant scope (Phase 3J, ADR-007 §4.9).
 *
 * Unlike Spring's static `GrantedAuthority` attached to the
 * Authentication at auth time, Medcore's authorities are
 * **tenant-scoped**: the same user is OWNER of tenant A and MEMBER
 * of tenant B and a stranger to tenant C. The authority set is
 * therefore resolved per-check, using the caller's live membership
 * data.
 *
 * [resolveFor] returns an empty set for:
 *   - A user with no membership in the target tenant.
 *   - A user with a SUSPENDED or REVOKED membership.
 *   - A target tenant that is SUSPENDED or ARCHIVED.
 *
 * Fail-closed: if anything goes wrong resolving the role, the
 * caller has no authority. [resolveMembership] is exposed
 * separately so [AuthzPolicy] implementations can distinguish
 * "not a member" from "suspended tenant" for the specific
 * [com.medcore.platform.write.WriteDenialReason].
 */
@Component
class AuthorityResolver(
    private val membershipLookup: TenantMembershipLookup,
) {

    /**
     * Returns the authority set [userId] holds in the tenant
     * identified by [tenantSlug]. Empty if not-a-member,
     * suspended, or tenant not active.
     */
    fun resolveFor(userId: UUID, tenantSlug: String): Set<MedcoreAuthority> {
        val resolved = membershipLookup.resolve(userId, tenantSlug) ?: return emptySet()
        if (resolved.tenantStatus != TenantStatus.ACTIVE) return emptySet()
        if (resolved.status != MembershipStatus.ACTIVE) return emptySet()
        return MembershipRoleAuthorities.forRole(resolved.role)
    }

    /**
     * Returns the raw resolved membership (when one exists) so
     * [AuthzPolicy] implementations can produce specific denial
     * reasons (tenant-suspended vs. membership-suspended vs.
     * not-a-member).
     */
    fun resolveMembership(userId: UUID, tenantSlug: String): ResolvedMembership? =
        membershipLookup.resolve(userId, tenantSlug)
}
