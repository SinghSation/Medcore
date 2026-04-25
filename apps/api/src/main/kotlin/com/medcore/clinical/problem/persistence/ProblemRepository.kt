package com.medcore.clinical.problem.persistence

import com.medcore.clinical.problem.model.ProblemStatus
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * Repository for [ProblemEntity] (Phase 4E.2; paginated as of
 * platform-pagination chunk E, ADR-009).
 *
 * Surface:
 *   - [findById] (inherited).
 *   - [saveAndFlush] (inherited).
 *   - [findFirstPage] / [findAfter] — paginated reads.
 *   - [countByTenantIdAndPatientIdAndStatus] — count helper.
 *
 * ### Sort axis
 *
 * `(status_priority, createdAt DESC, id DESC)` per ADR-009 §2.5.
 * `status_priority` is the SMALLINT column added in V29 — a
 * `GENERATED ALWAYS AS (CASE status …) STORED` mirror of `status`
 * with the platform-wide priority mapping (ACTIVE = 0,
 * INACTIVE = 1, RESOLVED = 2, ENTERED_IN_ERROR = 3). Maintained
 * by PG; the application never writes it. Same numeric mapping
 * as [com.medcore.clinical.problem.model.ProblemStatus.priority]
 * — adding a new enum value requires updating both.
 *
 * **RESOLVED ≠ INACTIVE** is load-bearing. RESOLVED occupies
 * the `2` slot — distinct from INACTIVE's `1`. The generated
 * column encodes the distinction in the DB itself; drift here
 * would collapse the 4E.2 invariant.
 *
 * ### Cursor walk semantics
 *
 * `findAfter(bucket, ts, id)` returns rows strictly AFTER the
 * encoded tuple in the `(bucket ASC, ts DESC, id DESC)` ordering:
 *
 *   `bucket > b_last`                            — next bucket
 *   OR `bucket = b_last AND createdAt < ts_last` — same bucket, older
 *   OR `bucket = b_last AND createdAt = ts_last AND id < id_last` — tiebreak
 *
 * V29's covering index `(tenant_id, patient_id, status_priority,
 * created_at, id)` matches the ORDER BY one-for-one — PG can
 * resolve the cursor walk as a single index scan with no Sort
 * step, regardless of patient size. The earlier raw-status
 * index could not satisfy the ORDER BY because Hibernate stores
 * the enum as TEXT and PG sorted it alphabetically rather than
 * by priority; switching to a generated column closes that gap.
 */
interface ProblemRepository : JpaRepository<ProblemEntity, UUID> {

    /**
     * First page of problems for a patient, paginated.
     * `(status_priority ASC, createdAt DESC, id DESC)`,
     * `LIMIT :limit` rows. ACTIVE rows always lead — the
     * MVP card render-first-page-only posture (ADR-009 §3.3)
     * relies on it.
     */
    @Query(
        """
        SELECT p FROM ProblemEntity p
         WHERE p.tenantId = :tenantId
           AND p.patientId = :patientId
         ORDER BY p.statusPriority,
                  p.createdAt DESC,
                  p.id DESC
        """,
    )
    fun findFirstPage(
        tenantId: UUID,
        patientId: UUID,
        limit: Limit,
    ): List<ProblemEntity>

    /**
     * Cursor walk: rows strictly AFTER `(bucket, ts, id)` in
     * the `(bucket ASC, createdAt DESC, id DESC)` ordering.
     * Compares the cursor's `:bucket` directly against
     * `p.statusPriority` (V29 generated column) so PG can
     * resolve the walk via the matching covering index with
     * no Sort step.
     */
    @Query(
        """
        SELECT p FROM ProblemEntity p
         WHERE p.tenantId = :tenantId
           AND p.patientId = :patientId
           AND (
             p.statusPriority > :bucket
             OR (p.statusPriority = :bucket AND p.createdAt < :ts)
             OR (p.statusPriority = :bucket AND p.createdAt = :ts AND p.id < :id)
           )
         ORDER BY p.statusPriority,
                  p.createdAt DESC,
                  p.id DESC
        """,
    )
    fun findAfter(
        tenantId: UUID,
        patientId: UUID,
        bucket: Int,
        ts: Instant,
        id: UUID,
        limit: Limit,
    ): List<ProblemEntity>

    /**
     * Helper kept around for callers that want a quick count
     * by status without a Kotlin filter — used by future
     * problem-list summary surfaces.
     */
    @Suppress("unused")
    @Query(
        """
        SELECT COUNT(p) FROM ProblemEntity p
         WHERE p.tenantId = :tenantId
           AND p.patientId = :patientId
           AND p.status = :status
        """,
    )
    fun countByTenantIdAndPatientIdAndStatus(
        tenantId: UUID,
        patientId: UUID,
        status: ProblemStatus,
    ): Long
}
