package com.medcore.tenancy.write

import com.medcore.identity.IdentityUserRepository
import com.medcore.platform.tenancy.MembershipStatus
import com.medcore.platform.write.WriteValidationException
import com.medcore.tenancy.persistence.TenantMembershipEntity
import com.medcore.tenancy.persistence.TenantMembershipRepository
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * Handler for [InviteTenantMembershipCommand] (Phase 3J.3).
 *
 * Runs inside the [com.medcore.platform.write.WriteGate]-owned
 * transaction, after policy approval + `TenancyRlsTxHook` has set
 * `app.current_user_id`.
 *
 * ### Target-user existence
 *
 * A missing `identity.user` row surfaces as
 * [WriteValidationException] → 422 `{field:"userId",
 * code:"user_not_found"}` rather than `EntityNotFoundException` →
 * 404. Rationale: returning 404 leaks user-existence to authenticated
 * admins. 422 is the same code returned for invalid-input; the
 * response body contains no value the caller didn't already
 * submit.
 *
 * ### Duplicate-membership handling
 *
 * No pre-check. Rely on the V6 `uq_tenancy_membership_tenant_user
 * UNIQUE (tenant_id, user_id)` constraint as the authoritative
 * guard — transaction-safe under concurrent writes. The 23505
 * SQLSTATE maps to 409 `resource.conflict` via Phase 3G.
 *
 * ### Self-invite
 *
 * Allowed at the policy level (the caller already has
 * `MEMBERSHIP_INVITE` authority, which requires an ACTIVE
 * membership). The unique constraint then refuses with 409 —
 * which is exactly the right signal ("you are already a member
 * of this tenant"). See `InviteTenantMembershipIntegrationTest.
 * self-invite returns 409`.
 */
@Component
class InviteTenantMembershipHandler(
    private val tenantRepository: TenantRepository,
    private val membershipRepository: TenantMembershipRepository,
    private val identityUserRepository: IdentityUserRepository,
    private val clock: Clock,
) {

    fun handle(command: InviteTenantMembershipCommand): MembershipSnapshot {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        if (!identityUserRepository.existsById(command.userId)) {
            throw WriteValidationException(field = "userId", code = "user_not_found")
        }

        val now = Instant.now(clock)
        val membership = TenantMembershipEntity(
            id = UUID.randomUUID(),
            tenantId = tenant.id,
            userId = command.userId,
            role = command.role,
            status = MembershipStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
            rowVersion = 0,
        )
        // V6 unique constraint catches duplicates transactionally;
        // Spring surfaces DataIntegrityViolationException → 3G
        // translates SQLSTATE 23505 to 409 resource.conflict.
        val saved = membershipRepository.save(membership)
        return MembershipSnapshot(
            id = saved.id,
            userId = saved.userId,
            role = saved.role,
            status = saved.status,
            tenantId = tenant.id,
            tenantSlug = tenant.slug,
            tenantDisplayName = tenant.displayName,
            tenantStatus = tenant.status,
        )
    }
}
