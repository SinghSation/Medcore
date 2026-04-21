package com.medcore.tenancy.persistence

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

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
}
