package com.medcore.clinical.allergy.persistence

import com.medcore.clinical.allergy.model.AllergyStatus
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * Repository for [AllergyEntity] (Phase 4E.1).
 *
 * Surface:
 *   - [findById] (inherited) — used by Update + Revoke handlers.
 *   - [saveAndFlush] (inherited) — used by all write handlers
 *     to surface `row_version` immediately for the auditor.
 *   - [findByTenantIdAndPatientIdOrdered] — the banner /
 *     management-view query.
 *
 * Banner query ordering: ACTIVE first, then INACTIVE, then
 * ENTERED_IN_ERROR; within each status, newest `created_at`
 * first. Reasoning — clinicians scan the banner top-to-bottom
 * for currently-relevant allergies; INACTIVE / retracted entries
 * sit below the fold for context but don't dominate the
 * critical-information area.
 *
 * The list query runs under V24's SELECT RLS envelope. A caller
 * who is not an active member of the tenant sees zero rows —
 * the empty result is a legitimate disclosure event still
 * audited via [com.medcore.clinical.allergy.read.ListPatientAllergiesAuditor].
 *
 * ArchUnit Rule 1 access perimeter mirrors the encounter /
 * patient repository pattern: accessible from `..write..`,
 * `..read..`, `..service..`, `..persistence..`, `..platform..`.
 *
 * ### Filter helper
 *
 * The banner UI typically wants ACTIVE-only; the management
 * view wants all statuses. We don't ship a separate query for
 * "ACTIVE-only" — the V24 composite index
 * `(tenant_id, patient_id, status)` makes filtering on the
 * client side or via a small in-handler `filter` cheap, and
 * keeping a single repository method limits the surface area
 * downstream policies must reason about.
 */
interface AllergyRepository : JpaRepository<AllergyEntity, UUID> {

    /**
     * All allergies for one patient, ordered ACTIVE first then
     * INACTIVE then ENTERED_IN_ERROR (status priority), then
     * newest-first by `created_at` within each status.
     *
     * Caller applies the banner-vs-management filter (typically
     * `items.filter { it.status == AllergyStatus.ACTIVE }` for
     * the banner). The repository returns the full set so the
     * audit row's `count` matches what RLS allowed the caller
     * to see, not what the UI later narrowed.
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
             WHEN com.medcore.clinical.allergy.model.AllergyStatus.ENTERED_IN_ERROR THEN 2
           END,
           a.createdAt DESC
        """,
    )
    fun findByTenantIdAndPatientIdOrdered(
        tenantId: UUID,
        patientId: UUID,
    ): List<AllergyEntity>

    /**
     * Helper kept around for callers that want only the banner-
     * relevant ACTIVE rows without applying a Kotlin filter.
     * Used by the future quick-count surface (count of ACTIVE
     * allergies on a patient banner header).
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
