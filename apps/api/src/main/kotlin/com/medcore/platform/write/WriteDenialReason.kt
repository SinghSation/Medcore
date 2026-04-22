package com.medcore.platform.write

/**
 * Closed enum of reasons a write can be denied at the authorization
 * boundary. Each reason carries:
 *
 * - A stable [code] slug used as the `reason` field on the
 *   `authz.write.denied` audit event. Never changes once shipped.
 * - An internal description for humans reading audit logs; NOT
 *   emitted to clients. Clients get the uniform "Access denied."
 *   message (enumeration rule from 3G).
 *
 * Adding a new reason requires: new enum entry + ADR-005-style
 * review-pack callout + test coverage asserting the denial path.
 * Renaming / removing a code is a breaking audit-contract change
 * requiring a superseding ADR.
 */
enum class WriteDenialReason(val code: String, val internalDescription: String) {
    NOT_A_MEMBER(
        code = "not_a_member",
        internalDescription = "Caller has no membership in the target tenant.",
    ),
    INSUFFICIENT_AUTHORITY(
        code = "insufficient_authority",
        internalDescription = "Caller has a membership but lacks the required authority for this mutation.",
    ),
    TENANT_SUSPENDED(
        code = "tenant_suspended",
        internalDescription = "Target tenant is not ACTIVE.",
    ),
    MEMBERSHIP_SUSPENDED(
        code = "membership_suspended",
        internalDescription = "Caller's membership in the target tenant is not ACTIVE.",
    ),
    SYSTEM_SCOPE_REQUIRED(
        code = "system_scope_required",
        internalDescription = "Mutation requires SYSTEM_WRITE authority; caller does not have it.",
    ),
}
