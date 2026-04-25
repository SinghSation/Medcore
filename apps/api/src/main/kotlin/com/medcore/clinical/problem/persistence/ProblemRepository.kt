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
 * `status_priority` is computed inline via JPQL CASE:
 *   ACTIVE = 0, INACTIVE = 1, RESOLVED = 2, ENTERED_IN_ERROR = 3.
 *
 * The mapping is locked in three places:
 *   - The DB query CASE (here)
 *   - [com.medcore.clinical.problem.model.ProblemStatus.priority] (Kotlin)
 *   - The cursor's `bucket` field
 *
 * **RESOLVED ≠ INACTIVE** is load-bearing. RESOLVED occupies
 * the `2` slot — distinct from INACTIVE's `1`. Drift here would
 * collapse the 4E.2 invariant.
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
 * V29's covering index `(tenant_id, patient_id, status,
 * created_at, id)` provides the prefix; the bucket comparison
 * is on the computed CASE so the planner may need a sort-then-
 * filter step at scale. For typical patient sizes this is
 * negligible.
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
         ORDER BY
           CASE p.status
             WHEN com.medcore.clinical.problem.model.ProblemStatus.ACTIVE THEN 0
             WHEN com.medcore.clinical.problem.model.ProblemStatus.INACTIVE THEN 1
             WHEN com.medcore.clinical.problem.model.ProblemStatus.RESOLVED THEN 2
             WHEN com.medcore.clinical.problem.model.ProblemStatus.ENTERED_IN_ERROR THEN 3
           END,
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
     * Three CASE expressions encode the same priority mapping
     * — keep them aligned with `ProblemStatus.priority`.
     */
    @Query(
        """
        SELECT p FROM ProblemEntity p
         WHERE p.tenantId = :tenantId
           AND p.patientId = :patientId
           AND (
             (CASE p.status
                WHEN com.medcore.clinical.problem.model.ProblemStatus.ACTIVE THEN 0
                WHEN com.medcore.clinical.problem.model.ProblemStatus.INACTIVE THEN 1
                WHEN com.medcore.clinical.problem.model.ProblemStatus.RESOLVED THEN 2
                WHEN com.medcore.clinical.problem.model.ProblemStatus.ENTERED_IN_ERROR THEN 3
              END) > :bucket
             OR
             ((CASE p.status
                 WHEN com.medcore.clinical.problem.model.ProblemStatus.ACTIVE THEN 0
                 WHEN com.medcore.clinical.problem.model.ProblemStatus.INACTIVE THEN 1
                 WHEN com.medcore.clinical.problem.model.ProblemStatus.RESOLVED THEN 2
                 WHEN com.medcore.clinical.problem.model.ProblemStatus.ENTERED_IN_ERROR THEN 3
               END) = :bucket
              AND p.createdAt < :ts)
             OR
             ((CASE p.status
                 WHEN com.medcore.clinical.problem.model.ProblemStatus.ACTIVE THEN 0
                 WHEN com.medcore.clinical.problem.model.ProblemStatus.INACTIVE THEN 1
                 WHEN com.medcore.clinical.problem.model.ProblemStatus.RESOLVED THEN 2
                 WHEN com.medcore.clinical.problem.model.ProblemStatus.ENTERED_IN_ERROR THEN 3
               END) = :bucket
              AND p.createdAt = :ts
              AND p.id < :id)
           )
         ORDER BY
           CASE p.status
             WHEN com.medcore.clinical.problem.model.ProblemStatus.ACTIVE THEN 0
             WHEN com.medcore.clinical.problem.model.ProblemStatus.INACTIVE THEN 1
             WHEN com.medcore.clinical.problem.model.ProblemStatus.RESOLVED THEN 2
             WHEN com.medcore.clinical.problem.model.ProblemStatus.ENTERED_IN_ERROR THEN 3
           END,
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
