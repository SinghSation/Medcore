package com.medcore.tenancy.service

import com.medcore.platform.tenancy.MembershipRole
import com.medcore.platform.tenancy.MembershipStatus
import com.medcore.platform.tenancy.ResolvedMembership
import com.medcore.platform.tenancy.TenantMembershipLookup
import com.medcore.platform.tenancy.TenantStatus
import com.medcore.tenancy.persistence.TenantEntity
import com.medcore.tenancy.persistence.TenantMembershipEntity
import com.medcore.tenancy.persistence.TenantMembershipRepository
import com.medcore.tenancy.persistence.TenantRepository
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Tenancy module public entry point.
 *
 * - Implements [TenantMembershipLookup] so the platform filter can resolve
 *   `X-Medcore-Tenant` against membership without importing anything from
 *   tenancy persistence.
 * - Exposes read methods for the tenancy controller: membership listing
 *   and per-slug membership lookup for the authenticated caller.
 *
 * Writes are intentionally out of scope for 3B.1 — tenant provisioning,
 * membership invitation, role changes all arrive with the admin surface
 * in later phases (tracked; not silently omitted).
 *
 * Never returns JPA entities across the boundary. Callers receive the
 * immutable projections [TenantSummary] / [MembershipDetail] (defined
 * here so tenancy's public surface is in one place) or the platform-level
 * [ResolvedMembership] when the platform port is called.
 *
 * TODO(phase-3C): emit `tenancy.context.set` / `tenancy.membership.list` /
 * `tenancy.membership.denied` audit events at each decision point
 * (ADR-003 Audit v1 — deferred along with the rest of audit).
 */
@Service
@Transactional(readOnly = true)
class TenancyService(
    private val tenantRepository: TenantRepository,
    private val membershipRepository: TenantMembershipRepository,
) : TenantMembershipLookup {

    override fun resolve(userId: UUID, slug: String): ResolvedMembership? {
        val tenant = tenantRepository.findBySlug(slug) ?: return null
        val membership = membershipRepository.findByTenantIdAndUserId(tenant.id, userId)
            ?: return null
        return toResolvedMembership(tenant, membership)
    }

    /**
     * Lists every membership this user has — ACTIVE, SUSPENDED, REVOKED —
     * paired with the tenant it belongs to. Callers filter as needed.
     *
     * Returns memberships in stable slug order so the response shape is
     * deterministic for clients and tests.
     */
    fun listMembershipsFor(userId: UUID): List<MembershipDetail> {
        val memberships = membershipRepository.findAllByUserId(userId)
        if (memberships.isEmpty()) return emptyList()
        val tenantsById = tenantRepository
            .findAllById(memberships.map { it.tenantId }.toSet())
            .associateBy { it.id }
        return memberships
            .mapNotNull { membership ->
                val tenant = tenantsById[membership.tenantId] ?: return@mapNotNull null
                toMembershipDetail(tenant, membership)
            }
            .sortedBy { it.tenant.slug }
    }

    fun findMembershipBySlug(userId: UUID, slug: String): MembershipDetail? {
        val tenant = tenantRepository.findBySlug(slug) ?: return null
        val membership = membershipRepository.findByTenantIdAndUserId(tenant.id, userId)
            ?: return null
        return toMembershipDetail(tenant, membership)
    }

    private fun toMembershipDetail(
        tenant: TenantEntity,
        membership: TenantMembershipEntity,
    ): MembershipDetail = MembershipDetail(
        membershipId = membership.id,
        userId = membership.userId,
        role = membership.role,
        status = membership.status,
        tenant = TenantSummary(
            id = tenant.id,
            slug = tenant.slug,
            displayName = tenant.displayName,
            status = tenant.status,
        ),
    )

    private fun toResolvedMembership(
        tenant: TenantEntity,
        membership: TenantMembershipEntity,
    ): ResolvedMembership = ResolvedMembership(
        tenantId = tenant.id,
        tenantSlug = tenant.slug,
        tenantDisplayName = tenant.displayName,
        tenantStatus = tenant.status,
        userId = membership.userId,
        membershipId = membership.id,
        role = membership.role,
        status = membership.status,
    )
}

/** Immutable projection of a tenant row. Internal classification. */
data class TenantSummary(
    val id: UUID,
    val slug: String,
    val displayName: String,
    val status: TenantStatus,
)

/** Immutable projection of a membership row, with its tenant inline. */
data class MembershipDetail(
    val membershipId: UUID,
    val userId: UUID,
    val role: MembershipRole,
    val status: MembershipStatus,
    val tenant: TenantSummary,
)
