package com.medcore.tenancy.service

import com.medcore.platform.audit.ActorType
import com.medcore.platform.audit.AuditAction
import com.medcore.platform.audit.AuditEventCommand
import com.medcore.platform.audit.AuditOutcome
import com.medcore.platform.audit.AuditWriter
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
 * Audit emission (ADR-003 §7):
 *
 *   - `tenancy.membership.list` — on every [listMembershipsFor] call
 *     (outcome = SUCCESS; `reason` carries a coarse `count=<n>` token so
 *     investigators can tell an empty list from a full one without
 *     joining other tables).
 *   - `tenancy.membership.denied` — on every denial decision inside this
 *     service: unknown slug, not-a-member, inactive membership, inactive
 *     tenant. Each denial carries a distinct short [DenialReason] code.
 *   - `tenancy.context.set` is emitted by
 *     [com.medcore.tenancy.context.TenantContextFilter] — the only place
 *     a tenant context is actually SET on the request. The per-slug
 *     lookup via `GET /tenants/{slug}/me` returns details but does NOT
 *     establish request-scoped context and therefore does not emit
 *     `tenancy.context.set`.
 *
 * Writes are out of scope for Phase 3B.1 and 3C — tenant provisioning,
 * membership invitation, and role changes arrive with the admin surface
 * in later phases.
 *
 * Never returns JPA entities across the boundary. Callers receive the
 * immutable projections [TenantSummary] / [MembershipDetail] /
 * [TenantMembershipResult].
 */
@Service
@Transactional
class TenancyService(
    private val tenantRepository: TenantRepository,
    private val membershipRepository: TenantMembershipRepository,
    private val auditWriter: AuditWriter,
) : TenantMembershipLookup {

    @Transactional(readOnly = true)  // pure read; filter writes audit OUTSIDE this tx
    override fun resolve(userId: UUID, slug: String): ResolvedMembership? {
        val tenant = tenantRepository.findBySlug(slug) ?: return null
        val membership = membershipRepository.findByTenantIdAndUserId(tenant.id, userId)
            ?: return null
        return toResolvedMembership(tenant, membership)
    }

    /**
     * Lists every ACTIVE-on-ACTIVE membership this user has, paired with
     * its tenant. Emits one `tenancy.membership.list` audit row per call.
     * Returns memberships in stable slug order.
     *
     * Not annotated `readOnly = true` because the audit row IS a write
     * inside this transaction; the audited action and the audit row
     * commit together (ADR-003 §2).
     */
    fun listMembershipsFor(userId: UUID): List<MembershipDetail> {
        val memberships = membershipRepository.findAllByUserId(userId)
        val result: List<MembershipDetail>
        if (memberships.isEmpty()) {
            result = emptyList()
        } else {
            val tenantsById = tenantRepository
                .findAllById(memberships.map { it.tenantId }.toSet())
                .associateBy { it.id }
            result = memberships
                .mapNotNull { membership ->
                    val tenant = tenantsById[membership.tenantId] ?: return@mapNotNull null
                    toMembershipDetail(tenant, membership)
                }
                .sortedBy { it.tenant.slug }
        }
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.TENANCY_MEMBERSHIP_LIST,
                actorType = ActorType.USER,
                actorId = userId,
                outcome = AuditOutcome.SUCCESS,
                reason = "count=${result.size}",
            ),
        )
        return result
    }

    /**
     * Resolves the caller's access to a tenant by slug, emitting exactly
     * one audit row regardless of outcome:
     *   - [TenantMembershipResult.Granted] — no audit here (the caller,
     *     typically `GET /tenants/{slug}/me`, does not SET a tenant
     *     context; it only reads a membership projection). If a future
     *     route uses the result to establish context, that route is
     *     responsible for emitting `tenancy.context.set`.
     *   - [TenantMembershipResult.Denied] — emits `tenancy.membership.denied`
     *     with the [DenialReason] code, atomically with the audited
     *     denial decision (ADR-003 §2). Therefore not `readOnly = true`.
     */
    fun findMembershipForCallerBySlug(userId: UUID, slug: String): TenantMembershipResult {
        val tenant = tenantRepository.findBySlug(slug)
        if (tenant == null) {
            emitDenied(userId, tenantId = null, reason = DenialReason.SLUG_UNKNOWN)
            return TenantMembershipResult.Denied(DenialReason.SLUG_UNKNOWN)
        }
        val membership = membershipRepository.findByTenantIdAndUserId(tenant.id, userId)
        if (membership == null) {
            emitDenied(userId, tenantId = tenant.id, reason = DenialReason.NOT_A_MEMBER)
            return TenantMembershipResult.Denied(DenialReason.NOT_A_MEMBER)
        }
        if (tenant.status != TenantStatus.ACTIVE) {
            emitDenied(userId, tenantId = tenant.id, reason = DenialReason.TENANT_INACTIVE)
            return TenantMembershipResult.Denied(DenialReason.TENANT_INACTIVE)
        }
        if (membership.status != MembershipStatus.ACTIVE) {
            emitDenied(userId, tenantId = tenant.id, reason = DenialReason.MEMBERSHIP_INACTIVE)
            return TenantMembershipResult.Denied(DenialReason.MEMBERSHIP_INACTIVE)
        }
        return TenantMembershipResult.Granted(toMembershipDetail(tenant, membership))
    }

    private fun emitDenied(userId: UUID, tenantId: UUID?, reason: DenialReason) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.TENANCY_MEMBERSHIP_DENIED,
                actorType = ActorType.USER,
                actorId = userId,
                tenantId = tenantId,
                outcome = AuditOutcome.DENIED,
                reason = reason.code,
            ),
        )
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

/**
 * Result of a per-slug membership lookup. Denial carries a
 * non-enumerating reason code so callers can audit / log the
 * distinction internally while the wire response remains uniform
 * (Rule 01, anti-enumeration).
 */
sealed interface TenantMembershipResult {
    data class Granted(val detail: MembershipDetail) : TenantMembershipResult
    data class Denied(val reason: DenialReason) : TenantMembershipResult
}

enum class DenialReason(val code: String) {
    SLUG_UNKNOWN("slug_unknown"),
    NOT_A_MEMBER("not_a_member"),
    MEMBERSHIP_INACTIVE("membership_inactive"),
    TENANT_INACTIVE("tenant_inactive"),
}
