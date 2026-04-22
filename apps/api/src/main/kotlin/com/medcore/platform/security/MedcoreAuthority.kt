package com.medcore.platform.security

import org.springframework.security.core.GrantedAuthority

/**
 * Closed enum of Medcore authorities (Phase 3J, ADR-007 §4.9).
 *
 * Each entry wraps a stable Spring `GrantedAuthority` string
 * (`"MEDCORE_TENANT_UPDATE"`, etc.) used by method security
 * expressions and `AuthzPolicy` implementations.
 *
 * **Granular by design.** Refinement #5 from the Phase 3J pressure
 * test split the coarse "MANAGE" authorities into specific actions
 * preemptively so future DELETE endpoints (and everything else)
 * don't force authority renames — renaming a shipped authority is
 * a breaking security-contract change.
 *
 * **Registry discipline** (ADR-005 §2.3 pattern):
 *   1. New entry here.
 *   2. Update `MembershipRoleAuthorities` map.
 *   3. Update `MembershipRoleAuthoritiesTest`.
 *   4. Review-pack callout.
 *   5. Renaming/removing requires a superseding ADR — same
 *      discipline as `ErrorCodes`, `AuditAction`, and the Phase
 *      3F.2 span-attribute allow list.
 */
enum class MedcoreAuthority(val role: String) : GrantedAuthority {
    // --- Tenancy authorities ---
    TENANT_READ("MEDCORE_TENANT_READ"),
    TENANT_UPDATE("MEDCORE_TENANT_UPDATE"),
    TENANT_DELETE("MEDCORE_TENANT_DELETE"),

    // --- Membership authorities ---
    MEMBERSHIP_READ("MEDCORE_MEMBERSHIP_READ"),
    MEMBERSHIP_INVITE("MEDCORE_MEMBERSHIP_INVITE"),
    MEMBERSHIP_ROLE_UPDATE("MEDCORE_MEMBERSHIP_ROLE_UPDATE"),
    MEMBERSHIP_REMOVE("MEDCORE_MEMBERSHIP_REMOVE"),

    // --- System-scope (bootstrap, admin ops) ---
    /**
     * Reserved for bootstrap / admin operations that must bypass
     * normal tenancy scoping — specifically, creating the FIRST
     * tenant before any tenant exists for the caller to be a
     * member of. No standard role grants this authority. Acquired
     * only via bootstrap IdP claim, elevated session, or
     * migrator-only DB path. See ADR-007 §4.5.
     */
    SYSTEM_WRITE("MEDCORE_SYSTEM_WRITE"),
    ;

    override fun getAuthority(): String = role
}
