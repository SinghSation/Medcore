package com.medcore.tenancy.write

import com.medcore.platform.tenancy.MembershipRole
import java.util.UUID

/**
 * Command for `POST /api/v1/tenants/{slug}/memberships` — direct
 * creation of an ACTIVE membership for an already-provisioned user
 * (Phase 3J.3, ADR-007 §4.9).
 *
 * "Invite" here matches the [com.medcore.platform.security.MedcoreAuthority.MEMBERSHIP_INVITE]
 * authority semantics; Phase 3J.3 does NOT implement an
 * email-token invitation flow (PENDING lifecycle, token table,
 * acceptance endpoint). That lands in a dedicated slice when a
 * pilot customer demands it — flagged as a carry-forward in the
 * roadmap ledger.
 *
 * [userId] must identify an existing `identity.user` row;
 * [InviteTenantMembershipValidator] enforces basic shape, and
 * [InviteTenantMembershipHandler] verifies existence via
 * `identityUserRepository.existsById`. Non-existence surfaces as
 * 422 `{field:"userId", code:"user_not_found"}` rather than 404 —
 * avoids user-existence leakage.
 */
data class InviteTenantMembershipCommand(
    val slug: String,
    val userId: UUID,
    val role: MembershipRole,
)
