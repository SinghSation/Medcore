package com.medcore.platform.security

import com.medcore.platform.write.WriteDenialReason

/**
 * Outcome of resolving the [MedcoreAuthority] set a principal holds
 * within a tenant scope (Phase 3J.2, ADR-007 §4.9, feedback round
 * post-3J.1 from the pressure test).
 *
 * The sealed shape replaces the earlier `Set<MedcoreAuthority>` +
 * `emptySet()`-means-denied contract. Collapsing every denial into
 * an empty set destroyed information useful to the audit log and
 * to future operator alerting: "tenant is suspended" and "user
 * never was a member" are the same signal downstream. The sealed
 * result preserves the reason for every denial so [AuthzPolicy]
 * implementations can forward it into
 * [com.medcore.platform.write.WriteAuthorizationException] and the
 * [com.medcore.platform.audit.AuditAction.AUTHZ_WRITE_DENIED] row
 * ends up with the specific code (`denial:tenant_suspended`,
 * `denial:membership_suspended`, `denial:not_a_member`).
 */
sealed interface AuthorityResolution {

    /**
     * Caller holds [authorities] in the target tenant. Empty-but-
     * [Granted] is never a valid state — if the user is active in
     * an active tenant, the role-authority map guarantees at least
     * the read authorities. [Denied] is the only path to "no
     * authorities."
     */
    data class Granted(val authorities: Set<MedcoreAuthority>) : AuthorityResolution

    /**
     * Caller holds no authority in the target tenant. [reason]
     * distinguishes:
     *
     *   - [WriteDenialReason.NOT_A_MEMBER] — no membership row
     *     exists, or the tenant slug does not resolve (the two
     *     are intentionally conflated so caller cannot enumerate
     *     tenants by probing for 404 vs 403).
     *   - [WriteDenialReason.MEMBERSHIP_SUSPENDED] — membership row
     *     exists with `status in (SUSPENDED, REVOKED)`.
     *   - [WriteDenialReason.TENANT_SUSPENDED] — membership is
     *     active but the target tenant is `SUSPENDED` or `ARCHIVED`.
     *
     * `INSUFFICIENT_AUTHORITY` is NOT a denial the resolver can
     * return — it is produced by the policy after comparing the
     * granted authority set against the command's requirement.
     */
    data class Denied(val reason: WriteDenialReason) : AuthorityResolution
}
