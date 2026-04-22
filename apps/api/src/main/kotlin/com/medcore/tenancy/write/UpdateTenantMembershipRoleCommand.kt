package com.medcore.tenancy.write

import com.medcore.platform.tenancy.MembershipRole
import java.util.UUID

/**
 * Command for
 * `PATCH /api/v1/tenants/{slug}/memberships/{membershipId}` —
 * change the `role` of an existing membership (Phase 3J.N,
 * ADR-007 §4.9).
 *
 * Role changes are the first membership-mutation type where two
 * non-input guards apply simultaneously:
 *
 * 1. **Escalation guard** (from 3J.3, extended): setting
 *    `newRole = OWNER` requires the caller hold `TENANT_DELETE`
 *    (OWNER-only). Prevents ADMIN from promoting another user
 *    to OWNER and inheriting that trust.
 *
 * 2. **Target-OWNER guard** (new in 3J.N): modifying a membership
 *    whose CURRENT role is OWNER requires the caller hold
 *    `TENANT_DELETE`. Prevents ADMIN from demoting an OWNER.
 *
 * 3. **Last-OWNER invariant** (new in 3J.N, ADR-007 §2.12):
 *    demoting the last ACTIVE OWNER is refused (409) even when
 *    the caller is that OWNER themselves. Enforced in the
 *    handler with a `PESSIMISTIC_WRITE` lock — concurrent
 *    demotions correctly serialise.
 */
data class UpdateTenantMembershipRoleCommand(
    val slug: String,
    val membershipId: UUID,
    val newRole: MembershipRole,
)
