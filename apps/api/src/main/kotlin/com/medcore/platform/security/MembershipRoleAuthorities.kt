package com.medcore.platform.security

import com.medcore.platform.tenancy.MembershipRole

/**
 * Compile-time-locked mapping from [MembershipRole] to the set of
 * [MedcoreAuthority] values a member with that role is granted
 * WITHIN their tenant scope (Phase 3J, ADR-007 §4.9).
 *
 * Changing any mapping requires: this update + test update in
 * `MembershipRoleAuthoritiesTest` + a review-pack callout naming
 * the role + authority set delta + the business reason.
 *
 * **Role definitions (locked as of 3J):**
 *
 * - **OWNER** — full authority over the tenant, including the
 *   ability to delete it. Typically the human who provisioned the
 *   tenant; in multi-owner scenarios, peers at the top of the
 *   authority tree.
 * - **ADMIN** — full authority EXCEPT tenant deletion. Day-to-day
 *   operational role — can modify tenant settings, invite and
 *   remove members, etc. Cannot dissolve the tenant.
 * - **MEMBER** — read-only. Can see the tenant they belong to and
 *   their own membership. Cannot mutate anything.
 *
 * SYSTEM_WRITE is never in any role's authority set — it's
 * acquired via bootstrap flows outside the tenancy model (see
 * ADR-007 §4.5).
 */
object MembershipRoleAuthorities {

    val OWNER_AUTHORITIES: Set<MedcoreAuthority> = setOf(
        MedcoreAuthority.TENANT_READ,
        MedcoreAuthority.TENANT_UPDATE,
        MedcoreAuthority.TENANT_DELETE,
        MedcoreAuthority.MEMBERSHIP_READ,
        MedcoreAuthority.MEMBERSHIP_INVITE,
        MedcoreAuthority.MEMBERSHIP_ROLE_UPDATE,
        MedcoreAuthority.MEMBERSHIP_REMOVE,
    )

    val ADMIN_AUTHORITIES: Set<MedcoreAuthority> = setOf(
        MedcoreAuthority.TENANT_READ,
        MedcoreAuthority.TENANT_UPDATE,
        // Intentional: no TENANT_DELETE. Admins operate within a
        // tenant; tenant dissolution belongs to owners.
        MedcoreAuthority.MEMBERSHIP_READ,
        MedcoreAuthority.MEMBERSHIP_INVITE,
        MedcoreAuthority.MEMBERSHIP_ROLE_UPDATE,
        MedcoreAuthority.MEMBERSHIP_REMOVE,
    )

    val MEMBER_AUTHORITIES: Set<MedcoreAuthority> = setOf(
        MedcoreAuthority.TENANT_READ,
        MedcoreAuthority.MEMBERSHIP_READ,
    )

    private val MAPPING: Map<MembershipRole, Set<MedcoreAuthority>> = mapOf(
        MembershipRole.OWNER to OWNER_AUTHORITIES,
        MembershipRole.ADMIN to ADMIN_AUTHORITIES,
        MembershipRole.MEMBER to MEMBER_AUTHORITIES,
    )

    /**
     * Returns the closed authority set for [role]. Roles not in the
     * mapping yield an empty set (fail-closed — unknown roles grant
     * nothing).
     */
    fun forRole(role: MembershipRole): Set<MedcoreAuthority> =
        MAPPING[role] ?: emptySet()
}
