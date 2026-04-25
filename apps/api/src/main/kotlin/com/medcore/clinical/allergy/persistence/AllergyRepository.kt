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
 * `status_priority` is the SMALLINT column added in V28 — a
 * `GENERATED ALWAYS AS (CASE status …) STORED` mirror of `status`
 * with the platform-wide priority mapping (ACTIVE = 0,
 * INACTIVE = 1, ENTERED_IN_ERROR = 3 — allergies skip the `2`
 * slot reserved for problems' RESOLVED). Maintained by PG; the
 * application never writes it. Same numeric mapping as
 * [com.medcore.clinical.allergy.model.AllergyStatus.priority] —
 * adding a new enum value requires updating both.
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
 * V28's covering index `(tenant_id, patient_id, status_priority,
 * created_at, id)` matches the ORDER BY one-for-one — PG can
 * resolve the cursor walk as a single index scan with no Sort
 * step, regardless of patient size. The earlier raw-status
 * index could not satisfy the ORDER BY because Hibernate stores
 * the enum as TEXT and PG sorted it alphabetically rather than
 * by priority; switching to a generated column closes that gap.
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
         ORDER BY a.statusPriority,
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
     * DESC, id DESC)` ordering. Compares the cursor's `:bucket`
     * directly against `a.statusPriority` (V28 generated column)
     * so PG can resolve the walk via the matching covering index
     * with no Sort step.
     */
    @Query(
        """
        SELECT a FROM AllergyEntity a
         WHERE a.tenantId = :tenantId
           AND a.patientId = :patientId
           AND (
             a.statusPriority > :bucket
             OR (a.statusPriority = :bucket AND a.createdAt < :ts)
             OR (a.statusPriority = :bucket AND a.createdAt = :ts AND a.id < :id)
           )
         ORDER BY a.statusPriority,
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
