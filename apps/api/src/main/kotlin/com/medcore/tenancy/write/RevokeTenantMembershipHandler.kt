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
 * Handler for [RevokeTenantMembershipCommand] (Phase 3J.N).
 *
 * Runs inside the [com.medcore.platform.write.WriteGate]-owned
 * transaction. Responsibilities:
 *
 * 1. Load tenant by slug, load membership by id; 404 if either
 *    missing or if membership belongs to a different tenant.
 * 2. **Target-OWNER guard** — if `target.role == OWNER` and the
 *    caller lacks `TENANT_DELETE`, throw
 *    `WriteAuthorizationException` (caught by WriteGate and routed
 *    through onDenied). Evaluated inside the tx after the RLS
 *    GUC is set.
 * 3. Idempotent REVOKED detection: if `target.status == REVOKED`,
 *    return snapshot with `changed = false` — auditor suppresses,
 *    client sees 204. Client retry is safe.
 * 4. Last-OWNER invariant: if `target.role == OWNER &&
 *    target.status == ACTIVE`, call
 *    [LastOwnerInvariant.assertAtLeastOneOtherActiveOwner] which
 *    acquires pessimistic locks + throws `WriteConflictException`
 *    if this would orphan the tenant.
 * 5. Transition `status: ACTIVE | SUSPENDED → REVOKED`, stamp
 *    `updated_at`. Save.
 */
@Component
class RevokeTenantMembershipHandler(
    private val tenantRepository: TenantRepository,
    private val membershipRepository: TenantMembershipRepository,
    private val lastOwnerInvariant: LastOwnerInvariant,
    private val authorityResolver: AuthorityResolver,
    private val clock: Clock,
) {

    fun handle(
        command: RevokeTenantMembershipCommand,
        context: WriteContext,
    ): RevokeSnapshot {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")
        val target = membershipRepository.findById(command.membershipId).orElse(null)
        if (target == null || target.tenantId != tenant.id) {
            throw EntityNotFoundException("membership not found: ${command.membershipId}")
        }

        // Target-OWNER guard (ADR-007 §4.9). ADMIN cannot revoke
        // an OWNER membership — only OWNER can.
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
        val priorStatus = target.status
        if (priorStatus == MembershipStatus.REVOKED) {
            return RevokeSnapshot(
                snapshot = toSnapshot(target, tenant),
                priorRole = priorRole,
                changed = false,
            )
        }

        val removesActiveOwner = priorRole == MembershipRole.OWNER
            && priorStatus == MembershipStatus.ACTIVE
        if (removesActiveOwner) {
            lastOwnerInvariant.assertAtLeastOneOtherActiveOwner(tenant.id)
        }

        target.status = MembershipStatus.REVOKED
        target.updatedAt = Instant.now(clock)
        val saved = membershipRepository.save(target)
        return RevokeSnapshot(
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
 * Handler output for revoke commands. [priorRole] feeds the
 * auditor's `prior_role:` emission; [changed] drives no-op
 * suppression when the membership is already REVOKED.
 */
data class RevokeSnapshot(
    val snapshot: MembershipSnapshot,
    val priorRole: MembershipRole,
    val changed: Boolean,
)
