package com.medcore.tenancy.write

import com.medcore.platform.security.AuthorityResolution
import com.medcore.platform.security.AuthorityResolver
import com.medcore.platform.security.MedcoreAuthority
import com.medcore.platform.tenancy.MembershipRole
import com.medcore.platform.tenancy.MembershipStatus
import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import com.medcore.tenancy.persistence.TenantMembershipRepository
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import java.time.Instant
import org.springframework.stereotype.Component

/**
 * Handler for [UpdateTenantMembershipRoleCommand] (Phase 3J.N).
 *
 * Runs inside the [com.medcore.platform.write.WriteGate]-owned
 * transaction, after the policy has approved the base-authority
 * checks. Responsibilities:
 *
 * 1. Load the tenant by slug (RLS-gated — caller has
 *    MEMBERSHIP_ROLE_UPDATE so tenant is visible).
 * 2. Load the target membership by id (V13 admin-read policy
 *    gives admins SELECT visibility across the tenant). 404 if
 *    missing OR if it belongs to a different tenant.
 * 3. **Target-OWNER guard** — if `target.role == OWNER` and the
 *    caller lacks `TENANT_DELETE`, throw
 *    `WriteAuthorizationException`. `WriteGate` catches this and
 *    routes through `onDenied` (Phase 3J.N framework extension).
 *    This guard lives HERE because the target row is visible only
 *    after the RLS GUC is set inside the gate's transaction.
 * 4. Short-circuit as a no-op if `target.role == command.newRole`.
 * 5. If the operation removes an OWNER from the active set, call
 *    [LastOwnerInvariant.assertAtLeastOneOtherActiveOwner] which
 *    acquires pessimistic locks on all active OWNER rows and
 *    throws `WriteConflictException` → 409 if this would orphan
 *    the tenant.
 * 6. Apply the role change + stamp `updated_at`.
 *
 * Takes the [com.medcore.platform.write.WriteContext] as an
 * explicit parameter (rather than relying on thread-local state)
 * so the target-OWNER guard can read the caller's userId from
 * `context.principal`. The controller passes the context via
 * closure capture in the `WriteGate.apply` lambda.
 */
@Component
class UpdateTenantMembershipRoleHandler(
    private val tenantRepository: TenantRepository,
    private val membershipRepository: TenantMembershipRepository,
    private val lastOwnerInvariant: LastOwnerInvariant,
    private val authorityResolver: AuthorityResolver,
    private val clock: Clock,
) {

    fun handle(
        command: UpdateTenantMembershipRoleCommand,
        context: WriteContext,
    ): RoleUpdateSnapshot {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")
        val target = membershipRepository.findById(command.membershipId).orElse(null)
        if (target == null || target.tenantId != tenant.id) {
            throw EntityNotFoundException("membership not found: ${command.membershipId}")
        }

        // Target-OWNER guard (ADR-007 §4.9). The caller's authorities
        // are re-resolved inside the gate's transaction so the RLS
        // GUC is set and the resolver can read the caller's
        // membership row. The duplicate resolution (policy + handler)
        // is acceptable overhead for clean authz layering.
        if (target.role == MembershipRole.OWNER) {
            val callerAuthorities = when (
                val resolution = authorityResolver.resolveFor(context.principal.userId, tenant.slug)
            ) {
                is AuthorityResolution.Granted -> resolution.authorities
                is AuthorityResolution.Denied ->
                    throw WriteAuthorizationException(resolution.reason)
            }
            if (MedcoreAuthority.TENANT_DELETE !in callerAuthorities) {
                throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
            }
        }

        val priorRole = target.role
        if (priorRole == command.newRole) {
            // No-op — same pattern as 3J.2's displayName-unchanged path.
            return RoleUpdateSnapshot(
                snapshot = toSnapshot(target, tenant),
                priorRole = priorRole,
                changed = false,
            )
        }

        val removesActiveOwner = priorRole == MembershipRole.OWNER
            && target.status == MembershipStatus.ACTIVE
            && command.newRole != MembershipRole.OWNER
        if (removesActiveOwner) {
            lastOwnerInvariant.assertAtLeastOneOtherActiveOwner(tenant.id)
        }

        target.role = command.newRole
        target.updatedAt = Instant.now(clock)
        val saved = membershipRepository.save(target)
        return RoleUpdateSnapshot(
            snapshot = toSnapshot(saved, tenant),
            priorRole = priorRole,
            changed = true,
        )
    }

    private fun toSnapshot(
        target: com.medcore.tenancy.persistence.TenantMembershipEntity,
        tenant: com.medcore.tenancy.persistence.TenantEntity,
    ): MembershipSnapshot = MembershipSnapshot(
        id = target.id,
        userId = target.userId,
        role = target.role,
        status = target.status,
        tenantId = tenant.id,
        tenantSlug = tenant.slug,
        tenantDisplayName = tenant.displayName,
        tenantStatus = tenant.status,
    )
}

/**
 * Handler output for role-update commands. Carries the
 * [MembershipSnapshot] for the response + the [priorRole] for the
 * auditor's `from:` / `to:` emission + the [changed] flag for
 * no-op suppression.
 */
data class RoleUpdateSnapshot(
    val snapshot: MembershipSnapshot,
    val priorRole: MembershipRole,
    val changed: Boolean,
)
