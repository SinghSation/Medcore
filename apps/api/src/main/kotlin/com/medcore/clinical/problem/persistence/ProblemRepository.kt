package com.medcore.clinical.problem.persistence

import com.medcore.clinical.problem.model.ProblemStatus
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * Repository for [ProblemEntity] (Phase 4E.2).
 *
 * Surface:
 *   - [findById] (inherited) — used by Update / Resolve /
 *     Revoke handlers.
 *   - [saveAndFlush] (inherited) — used by all write handlers
 *     to surface `row_version` immediately for the auditor.
 *   - [findByTenantIdAndPatientIdOrdered] — the chart-context
 *     list query.
 *
 * ### List ordering
 *
 * ACTIVE first, then INACTIVE, then RESOLVED, then
 * ENTERED_IN_ERROR. Within each status: newest `created_at`
 * first.
 *
 * **RESOLVED ≠ INACTIVE — see [ProblemStatus] KDoc.**
 * The ordering reflects clinical priority for chart-context
 * scanning: a clinician scanning the problem list cares
 * most about ACTIVE problems, then INACTIVE ones (could
 * recur), then RESOLVED ones (historical context only),
 * then ENTERED_IN_ERROR (data-quality artifact). Bundling
 * INACTIVE and RESOLVED together would obscure the
 * "could recur" signal.
 *
 * The list query runs under V25's SELECT RLS envelope. A
 * caller who is not an active member of the tenant sees
 * zero rows — the empty result is a legitimate disclosure
 * event still audited via
 * `com.medcore.clinical.problem.read.ListPatientProblemsAuditor`.
 *
 * ArchUnit Rule 1 access perimeter mirrors the encounter /
 * patient / allergy repository pattern: accessible from
 * `..write..`, `..read..`, `..service..`, `..persistence..`,
 * `..platform..`.
 */
interface ProblemRepository : JpaRepository<ProblemEntity, UUID> {

    /**
     * All problems for one patient, ordered ACTIVE first then
     * INACTIVE then RESOLVED then ENTERED_IN_ERROR (status
     * priority — preserves the RESOLVED ≠ INACTIVE signal in
     * the rendering order), then newest-first by `created_at`
     * within each status.
     *
     * Caller can apply a chart-context filter (typically the
     * UI hides ENTERED_IN_ERROR by default in the management
     * surface, but lets a clinician toggle them in for data-
     * audit purposes). The repository returns the full set so
     * the audit row's `count` matches what RLS allowed the
     * caller to see, not what the UI later narrowed.
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
           p.createdAt DESC
        """,
    )
    fun findByTenantIdAndPatientIdOrdered(
        tenantId: UUID,
        patientId: UUID,
    ): List<ProblemEntity>

    /**
     * Helper kept around for callers that want a quick count
     * by status without a Kotlin filter — used by future
     * problem-list summary surfaces (e.g., a header chip
     * "5 active problems"). Mirrors the same helper on
     * `AllergyRepository`.
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
