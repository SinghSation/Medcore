package com.medcore.tenancy.write

import java.util.UUID

/**
 * Command for
 * `DELETE /api/v1/tenants/{slug}/memberships/{membershipId}` —
 * soft-delete a membership by transitioning its status to
 * `REVOKED` (Phase 3J.N, ADR-007 §4.9).
 *
 * Semantic: "revoke" rather than "remove" because the row is
 * preserved with `status = REVOKED` so audit `resource_id`
 * references remain resolvable. `DELETE` is the correct HTTP
 * verb from the client's perspective (the membership vanishes
 * from the ACTIVE view).
 *
 * Guards: base [com.medcore.platform.security.MedcoreAuthority.MEMBERSHIP_REMOVE]
 * + target-OWNER guard (caller must hold TENANT_DELETE to revoke
 * an OWNER membership) + last-OWNER invariant.
 *
 * Idempotency: revoking an already-REVOKED membership returns
 * 204 silently (no state change, no audit row) — parallel to
 * 3J.2's no-op displayName path.
 */
data class RevokeTenantMembershipCommand(
    val slug: String,
    val membershipId: UUID,
)
