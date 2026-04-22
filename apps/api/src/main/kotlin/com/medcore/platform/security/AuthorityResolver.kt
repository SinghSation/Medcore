package com.medcore.platform.security

import com.medcore.platform.tenancy.MembershipStatus
import com.medcore.platform.tenancy.ResolvedMembership
import com.medcore.platform.tenancy.TenantMembershipLookup
import com.medcore.platform.tenancy.TenantStatus
import com.medcore.platform.write.WriteDenialReason
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
 * ### Return shape (Phase 3J.2)
 *
 * [resolveFor] returns an [AuthorityResolution] sealed type:
 *
 *   - [AuthorityResolution.Granted] — caller holds the given
 *     authority set in the target tenant.
 *   - [AuthorityResolution.Denied] — caller has no authority; the
 *     [WriteDenialReason] distinguishes "not a member,"
 *     "membership suspended," and "tenant suspended" so the
 *     downstream [com.medcore.platform.audit.AuditAction.AUTHZ_WRITE_DENIED]
 *     row carries a compliance-useful denial code rather than a
 *     coarse "empty set."
 *
 * Fail-closed: if anything goes wrong resolving the role, the
 * caller has no authority; default is [AuthorityResolution.Denied]
 * with [WriteDenialReason.NOT_A_MEMBER].
 *
 * ### Known limitation: MEMBERSHIP_SUSPENDED collapses to NOT_A_MEMBER
 *
 * V8's read-side RLS on `tenancy.tenant` requires an ACTIVE
 * membership for the tenant row to be visible. When the caller's
 * membership is SUSPENDED or REVOKED, `TenantMembershipLookup.resolve`
 * returns `null` (the tenant row is invisible), and this resolver
 * then returns [WriteDenialReason.NOT_A_MEMBER] rather than
 * [WriteDenialReason.MEMBERSHIP_SUSPENDED].
 *
 * `TENANT_SUSPENDED` distinguishes correctly because the caller's
 * membership is ACTIVE (tenant row is visible; resolver inspects
 * `tenantStatus`).
 *
 * Closing this collapse requires either (a) changing V8 to allow
 * suspended members to see their own tenant row, or (b) adding a
 * `tenancy.resolve_authority(slug, user_id)` SECURITY DEFINER
 * function in a future migration (V13+). Tracked as a carry-forward
 * from 3J.2. Until then, compliance forensics distinguish suspended
 * members from strangers via the `tenancy.tenant_membership` table
 * directly (the row still exists; RLS hides only cross-table joins).
 */
@Component
class AuthorityResolver(
    private val membershipLookup: TenantMembershipLookup,
) {

    /**
     * Resolves [userId]'s authorities in the tenant identified by
     * [tenantSlug]. Returns a sealed [AuthorityResolution].
     *
     * Unknown slugs and missing memberships are deliberately
     * collapsed into [WriteDenialReason.NOT_A_MEMBER] so a non-
     * member cannot distinguish "this tenant exists" from "this
     * tenant does not exist" (tenant-enumeration protection, ADR-007
     * §4.9).
     */
    fun resolveFor(userId: UUID, tenantSlug: String): AuthorityResolution {
        val resolved = membershipLookup.resolve(userId, tenantSlug)
            ?: return AuthorityResolution.Denied(WriteDenialReason.NOT_A_MEMBER)
        if (resolved.tenantStatus != TenantStatus.ACTIVE) {
            return AuthorityResolution.Denied(WriteDenialReason.TENANT_SUSPENDED)
        }
        if (resolved.status != MembershipStatus.ACTIVE) {
            return AuthorityResolution.Denied(WriteDenialReason.MEMBERSHIP_SUSPENDED)
        }
        return AuthorityResolution.Granted(MembershipRoleAuthorities.forRole(resolved.role))
    }

    /**
     * Raw resolved membership (when one exists) for callers that
     * need tenant IDs or role strings alongside authority resolution
     * — e.g., tenancy controllers that render membership responses.
     * Kept separate so the write-path always goes through
     * [resolveFor] and never bypasses the sealed denial contract.
     */
    fun resolveMembership(userId: UUID, tenantSlug: String): ResolvedMembership? =
        membershipLookup.resolve(userId, tenantSlug)
}
