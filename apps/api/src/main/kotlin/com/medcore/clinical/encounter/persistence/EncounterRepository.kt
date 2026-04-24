package com.medcore.clinical.encounter.persistence

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * Repository for [EncounterEntity] (Phase 4C.1..4C.3).
 *
 * Surface:
 *   - [findById] (inherited) — used by `GetEncounterHandler`.
 *   - [saveAndFlush] (inherited) — used by `StartEncounterHandler`
 *     to surface the generated row_version immediately.
 *   - [findByTenantIdAndPatientIdOrdered] — Phase 4C.3 per-patient
 *     list surface.
 *
 * ArchUnit Rule 1 access perimeter matches the patient repository
 * pattern: accessible from `..write..`, `..read..`, `..service..`,
 * `..persistence..`, `..platform..`.
 */
interface EncounterRepository : JpaRepository<EncounterEntity, UUID> {
    /**
     * Per-patient encounter list, newest-first by `started_at`.
     * Runs under V18's SELECT RLS policy + the `tenant_id +
     * patient_id` composite predicate — rows the caller should
     * not see are filtered at the DB layer, a zero-row result
     * means "no encounters yet for this patient" (a legitimate
     * disclosure event still audited once via the list auditor).
     *
     * Un-paginated in 4C.3 — bounded in practice. Cursor-based
     * pagination is a later slice if tenant scale warrants.
     */
    @Query(
        """
        SELECT e FROM EncounterEntity e
         WHERE e.tenantId = :tenantId
           AND e.patientId = :patientId
         ORDER BY e.startedAt DESC, e.createdAt DESC
        """,
    )
    fun findByTenantIdAndPatientIdOrdered(
        tenantId: UUID,
        patientId: UUID,
    ): List<EncounterEntity>

    /**
     * The single IN_PROGRESS encounter on a patient within a
     * tenant, if any (Phase 4C.4). `null` when none exists.
     *
     * V22's partial unique index
     * `uq_clinical_encounter_one_in_progress_per_patient`
     * guarantees at most one row matches this predicate — the
     * handler's pre-check uses this method to 409 on double-
     * start, and the 409 body's `existingEncounterId` comes
     * straight from the returned row.
     *
     * Runs under V18's SELECT RLS envelope.
     */
    @Query(
        """
        SELECT e FROM EncounterEntity e
         WHERE e.tenantId = :tenantId
           AND e.patientId = :patientId
           AND e.status = com.medcore.clinical.encounter.model.EncounterStatus.IN_PROGRESS
        """,
    )
    fun findInProgressByTenantIdAndPatientId(
        tenantId: UUID,
        patientId: UUID,
    ): EncounterEntity?
}
