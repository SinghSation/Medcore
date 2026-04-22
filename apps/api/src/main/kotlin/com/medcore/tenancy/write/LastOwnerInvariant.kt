package com.medcore.tenancy.write

import com.medcore.platform.tenancy.MembershipRole
import com.medcore.platform.tenancy.MembershipStatus
import com.medcore.platform.write.WriteConflictException
import com.medcore.tenancy.persistence.TenantMembershipRepository
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * The last-OWNER invariant (ADR-007 §2.12, Phase 3J.N).
 *
 * Every tenant in Medcore MUST have at least one ACTIVE OWNER at
 * all times. Without this guarantee, a tenant can become
 * permanently unmanageable — no OWNER → no `TENANT_DELETE`, no
 * `TENANT_UPDATE` on escalation paths, no ability to repair via
 * standard authority paths. The only recovery would be a manual
 * DB edit by a platform operator, which is exactly the kind of
 * break-glass operation we do NOT want to need.
 *
 * This is Medcore's FIRST first-class invariant that depends on
 * **aggregate state** across multiple rows rather than the command
 * plus a single target row. Every future invariant of the same
 * shape (minimum-active-users, required-role-present, cross-entity
 * constraints) adopts this pattern.
 *
 * ### Enforcement layer (3J.N: application-layer primary)
 *
 * Handler-layer check using a `PESSIMISTIC_WRITE` row lock on all
 * currently-active OWNER rows for the tenant (see
 * [TenantMembershipRepository.findAndLockByTenantRoleStatus]). The
 * lock serialises concurrent demotions/revocations that would race
 * past a naïve count-and-check.
 *
 * Callers invoke [assertAtLeastOneOtherActiveOwner] ONLY when the
 * pending operation would REMOVE one OWNER from the active set —
 * i.e., role change `OWNER → non-OWNER`, or revoke of an ACTIVE
 * `OWNER` membership. Promoting `non-OWNER → OWNER` or revoking a
 * non-OWNER requires no check; those operations cannot drop the
 * active-OWNER count.
 *
 * ### Enforcement layer (future: DB trigger)
 *
 * A deferred `CHECK` trigger at transaction-commit time is
 * tracked as a V13+ carry-forward (paired with
 * `tenancy.resolve_authority` SECURITY DEFINER function). That
 * trigger closes the phantom-read window against a concurrent
 * "new OWNER" INSERT and provides true defense-in-depth against
 * any future app path that bypasses the handler.
 */
@Component
class LastOwnerInvariant(
    private val membershipRepository: TenantMembershipRepository,
) {

    /**
     * Asserts the tenant has at least ONE OTHER active OWNER
     * besides the one the caller is about to remove (via role
     * change or revocation). Throws
     * [WriteConflictException] with code
     * `last_owner_in_tenant` otherwise — Phase 3G maps to 409
     * with `details.reason = "last_owner_in_tenant"`.
     *
     * Acquires `PESSIMISTIC_WRITE` locks on every active OWNER
     * row in the tenant. MUST be called inside the
     * WriteGate-owned transaction so the locks survive until the
     * pending mutation commits.
     *
     * Contract: invoke ONLY when the caller's pending operation
     * removes one OWNER from the active set. Invoking on an op
     * that preserves the count (or adds to it) would incorrectly
     * reject single-OWNER tenants.
     */
    fun assertAtLeastOneOtherActiveOwner(tenantId: UUID) {
        val activeOwners = membershipRepository.findAndLockByTenantRoleStatus(
            tenantId = tenantId,
            role = MembershipRole.OWNER,
            status = MembershipStatus.ACTIVE,
        )
        if (activeOwners.size <= 1) {
            throw WriteConflictException(CODE_LAST_OWNER)
        }
    }

    companion object {
        /**
         * Audit / wire-facing slug for the last-OWNER rejection.
         * Surfaces as `details.reason` in the 409 response body;
         * do NOT rename without a superseding ADR (wire contract).
         */
        const val CODE_LAST_OWNER: String = "last_owner_in_tenant"
    }
}
