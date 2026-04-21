package com.medcore.tenancy.persistence

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Tenancy-internal. All external access funnels through
 * [com.medcore.tenancy.service.TenancyService] (Rule 00 — no cross-module
 * table access).
 */
interface TenantRepository : JpaRepository<TenantEntity, UUID> {
    fun findBySlug(slug: String): TenantEntity?
}
