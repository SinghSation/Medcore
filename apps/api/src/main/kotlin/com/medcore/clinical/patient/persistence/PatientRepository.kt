package com.medcore.clinical.patient.persistence

import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Repository for [PatientEntity] (Phase 4A.1).
 *
 * ArchUnit Rule 1 enforces access perimeter: this repository
 * is accessible only from `..write..`, `..read..`, `..service..`,
 * `..persistence..`, `..platform..`, or `..identity..` packages.
 *
 * ### Query surface
 *
 * - [findById] / [saveAndFlush] — inherited from [JpaRepository],
 *   used by `CreatePatientHandler`, `UpdatePatientDemographicsHandler`,
 *   and `GetPatientHandler`.
 * - [findByTenantIdPaged] + [countByTenantId] — Phase 4B.1 list
 *   queries. Both run under the caller's `medcore_app` RLS
 *   envelope, so the count reflects visible rows (not raw
 *   tenant population).
 *
 * ### Ordering invariant
 *
 * `ORDER BY created_at DESC, id DESC` is the single supported
 * sort key for list reads. The composite (`created_at`, `id`)
 * is deterministic: `created_at` ties between rows inserted
 * in the same microsecond are broken by `id` — no client-visible
 * reordering between consecutive page fetches. Sort controls are
 * deliberately NOT exposed at the HTTP layer in 4B.1 (scope
 * discipline; adding them later is additive).
 */
interface PatientRepository : JpaRepository<PatientEntity, UUID> {

    /**
     * Page of visible patients for a tenant, newest first.
     * Runs under V14 `p_patient_select` RLS — cross-tenant
     * and `status = 'DELETED'` rows are filtered at the DB
     * layer (not here).
     */
    @Query(
        """
        SELECT p FROM PatientEntity p
         WHERE p.tenantId = :tenantId
         ORDER BY p.createdAt DESC, p.id DESC
        """,
    )
    fun findByTenantIdPaged(
        @Param("tenantId") tenantId: UUID,
        pageable: Pageable,
    ): List<PatientEntity>

    /**
     * Count of visible patients for a tenant. Same RLS envelope
     * as [findByTenantIdPaged] — the count matches what the
     * caller can actually page through. Intentionally a
     * separate query from [findByTenantIdPaged] so the handler
     * can decide whether to incur the cost (e.g., a future
     * cursor-based slice skips the count entirely).
     */
    @Query("SELECT COUNT(p) FROM PatientEntity p WHERE p.tenantId = :tenantId")
    fun countByTenantId(@Param("tenantId") tenantId: UUID): Long
}
