package com.medcore.clinical.allergy.persistence

import com.medcore.clinical.allergy.model.AllergyStatus
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * Repository for [AllergyEntity] (Phase 4E.1; paginated as of
 * platform-pagination chunk D, ADR-009).
 *
 * Surface:
 *   - [findById] (inherited) — used by Update + Revoke handlers.
 *   - [saveAndFlush] (inherited) — used by all write handlers
 *     to surface `row_version` immediately for the auditor.
 *   - [findFirstPage] / [findAfter] — paginated banner / management
 *     reads.
 *   - [countByTenantIdAndPatientIdAndStatus] — count helper.
 *
 * ### Sort axis
 *
 * `(status_priority, createdAt DESC, id DESC)` per ADR-009 §2.5.
 * `status_priority` is computed inline via a JPQL CASE
 * expression: ACTIVE = 0, INACTIVE = 1, ENTERED_IN_ERROR = 3.
 * Same mapping as
 * [com.medcore.clinical.allergy.model.AllergyStatus.priority] —
 * keep them in sync.
 *
 * ACTIVE rows always sort first (priority 0); the first page
 * therefore always contains every ACTIVE row up to pageSize.
 * Cards rendering only the first page never miss currently-
 * active allergies (load-bearing per ADR-009 §3.3).
 *
 * ### Cursor walk semantics
 *
 * `findAfter(bucket, ts, id)` returns rows strictly AFTER the
 * encoded tuple in the `(bucket ASC, ts DESC, id DESC)` ordering:
 *
 *   `bucket > b_last`                                — next bucket
 *   OR `bucket = b_last AND createdAt < ts_last`     — same bucket, older
 *   OR `bucket = b_last AND createdAt = ts_last AND id < id_last` — tiebreak
 *
 * V28's covering index `(tenant_id, patient_id, status,
 * created_at, id)` lets PG resolve the prefix `(tenant_id,
 * patient_id)` directly; the bucket comparison is on the
 * computed CASE so the planner may need a sort-then-filter
 * step at scale. For typical patient sizes (<50 rows total)
 * this is negligible.
 */
interface AllergyRepository : JpaRepository<AllergyEntity, UUID> {

    /**
     * First page of allergies for a patient, paginated.
     * `(status_priority ASC, createdAt DESC, id DESC)` ordering;
     * `LIMIT :limit` rows.
     */
    @Query(
        """
        SELECT a FROM AllergyEntity a
         WHERE a.tenantId = :tenantId
           AND a.patientId = :patientId
         ORDER BY
           CASE a.status
             WHEN com.medcore.clinical.allergy.model.AllergyStatus.ACTIVE THEN 0
             WHEN com.medcore.clinical.allergy.model.AllergyStatus.INACTIVE THEN 1
             WHEN com.medcore.clinical.allergy.model.AllergyStatus.ENTERED_IN_ERROR THEN 3
           END,
           a.createdAt DESC,
           a.id DESC
        """,
    )
    fun findFirstPage(
        tenantId: UUID,
        patientId: UUID,
        limit: Limit,
    ): List<AllergyEntity>

    /**
     * Cursor walk: rows strictly AFTER the given
     * `(bucket, ts, id)` tuple in the `(bucket ASC, createdAt
     * DESC, id DESC)` ordering.
     *
     * The CASE expression computes status_priority inline so
     * the WHERE clause stays declarative. A `:bucket` parameter
     * compares against the computed column.
     */
    @Query(
        """
        SELECT a FROM AllergyEntity a
         WHERE a.tenantId = :tenantId
           AND a.patientId = :patientId
           AND (
             (CASE a.status
                WHEN com.medcore.clinical.allergy.model.AllergyStatus.ACTIVE THEN 0
                WHEN com.medcore.clinical.allergy.model.AllergyStatus.INACTIVE THEN 1
                WHEN com.medcore.clinical.allergy.model.AllergyStatus.ENTERED_IN_ERROR THEN 3
              END) > :bucket
             OR
             ((CASE a.status
                 WHEN com.medcore.clinical.allergy.model.AllergyStatus.ACTIVE THEN 0
                 WHEN com.medcore.clinical.allergy.model.AllergyStatus.INACTIVE THEN 1
                 WHEN com.medcore.clinical.allergy.model.AllergyStatus.ENTERED_IN_ERROR THEN 3
               END) = :bucket
              AND a.createdAt < :ts)
             OR
             ((CASE a.status
                 WHEN com.medcore.clinical.allergy.model.AllergyStatus.ACTIVE THEN 0
                 WHEN com.medcore.clinical.allergy.model.AllergyStatus.INACTIVE THEN 1
                 WHEN com.medcore.clinical.allergy.model.AllergyStatus.ENTERED_IN_ERROR THEN 3
               END) = :bucket
              AND a.createdAt = :ts
              AND a.id < :id)
           )
         ORDER BY
           CASE a.status
             WHEN com.medcore.clinical.allergy.model.AllergyStatus.ACTIVE THEN 0
             WHEN com.medcore.clinical.allergy.model.AllergyStatus.INACTIVE THEN 1
             WHEN com.medcore.clinical.allergy.model.AllergyStatus.ENTERED_IN_ERROR THEN 3
           END,
           a.createdAt DESC,
           a.id DESC
        """,
    )
    fun findAfter(
        tenantId: UUID,
        patientId: UUID,
        bucket: Int,
        ts: Instant,
        id: UUID,
        limit: Limit,
    ): List<AllergyEntity>

    /**
     * Helper kept around for callers that want only the banner-
     * relevant ACTIVE rows without applying a Kotlin filter.
     * Used by future quick-count surfaces.
     */
    @Suppress("unused")
    @Query(
        """
        SELECT COUNT(a) FROM AllergyEntity a
         WHERE a.tenantId = :tenantId
           AND a.patientId = :patientId
           AND a.status = :status
        """,
    )
    fun countByTenantIdAndPatientIdAndStatus(
        tenantId: UUID,
        patientId: UUID,
        status: AllergyStatus,
    ): Long
}
