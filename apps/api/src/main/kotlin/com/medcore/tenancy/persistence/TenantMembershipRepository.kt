package com.medcore.tenancy.persistence

import com.medcore.platform.tenancy.MembershipRole
import com.medcore.platform.tenancy.MembershipStatus
import jakarta.persistence.LockModeType
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Tenancy-internal. The entity stores `tenant_id` and `user_id` as raw
 * UUIDs (intentional — see [TenantMembershipEntity]), so slug-based
 * lookups go through the service layer, which resolves the tenant first
 * and then queries membership by IDs.
 */
interface TenantMembershipRepository : JpaRepository<TenantMembershipEntity, UUID> {

    /** Memberships this user has, regardless of status — filter in the service. */
    fun findAllByUserId(userId: UUID): List<TenantMembershipEntity>

    fun findByTenantIdAndUserId(tenantId: UUID, userId: UUID): TenantMembershipEntity?

    /**
     * Acquires a `PESSIMISTIC_WRITE` row lock on every currently-
     * active OWNER membership in the given tenant (Phase 3J.N,
     * ADR-007 §2.12 last-OWNER invariant).
     *
     * ### Why the lock
     *
     * At PostgreSQL's default READ COMMITTED isolation, a naïve
     * `SELECT COUNT(*)` + app-layer check suffers a race: two
     * concurrent demotion transactions each read `count=2`, each
     * concludes "safe," each commits, and the tenant ends with
     * zero active OWNERs. The lock serialises competing demotions
     * at the row level — the second tx blocks on `SELECT ... FOR
     * UPDATE`, re-reads after the first commits, sees `count=1`,
     * and correctly rejects.
     *
     * ### What the lock does NOT prevent
     *
     * A concurrent INSERT of a brand-new OWNER is a phantom read
     * at READ COMMITTED — no row exists yet to lock. Consequence:
     * the invariant could theoretically reject a demotion that
     * would have been safe had the concurrent INSERT landed first.
     * Acceptable under the conservative-failure principle (we
     * never BREAK the invariant, we only occasionally reject an
     * operation that would have been fine). Future V13+ deferred
     * trigger closes this phantom window at commit time if it
     * becomes material.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT m FROM TenantMembershipEntity m
         WHERE m.tenantId = :tenantId
           AND m.role = :role
           AND m.status = :status
        """,
    )
    fun findAndLockByTenantRoleStatus(
        @Param("tenantId") tenantId: UUID,
        @Param("role") role: MembershipRole,
        @Param("status") status: MembershipStatus,
    ): List<TenantMembershipEntity>
}
