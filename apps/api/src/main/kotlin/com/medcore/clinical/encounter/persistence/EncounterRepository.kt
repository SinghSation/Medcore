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
     * Per-patient encounter list, newest-first by `created_at`,
     * tied on `id DESC` (paginated as of platform-pagination
     * chunk C, ADR-009 §2.5).
     *
     * Runs under V18's SELECT RLS policy + V27's covering
     * index `(tenant_id, patient_id, created_at, id)`.
     *
     * Sort axis is `createdAt`, not `startedAt`, because
     * cursor pagination needs a non-null primary axis and
     * `startedAt` is nullable on the entity. For the current
     * IN_PROGRESS-on-create flow, `startedAt == createdAt` —
     * observably identical ordering. Documented in
     * V27__encounter_pagination_index.sql.
     */
    @Query(
        """
        SELECT e FROM EncounterEntity e
         WHERE e.tenantId = :tenantId
           AND e.patientId = :patientId
         ORDER BY e.createdAt DESC, e.id DESC
        """,
    )
    fun findFirstPage(
        tenantId: UUID,
        patientId: UUID,
        limit: org.springframework.data.domain.Limit,
    ): List<EncounterEntity>

    /**
     * Cursor walk: rows strictly AFTER the given `(createdAt, id)`
     * tuple in the `(createdAt DESC, id DESC)` ordering.
     */
    @Query(
        """
        SELECT e FROM EncounterEntity e
         WHERE e.tenantId = :tenantId
           AND e.patientId = :patientId
           AND ( e.createdAt < :ts
                 OR ( e.createdAt = :ts AND e.id < :id ) )
         ORDER BY e.createdAt DESC, e.id DESC
        """,
    )
    fun findAfter(
        tenantId: UUID,
        patientId: UUID,
        ts: java.time.Instant,
        id: UUID,
        limit: org.springframework.data.domain.Limit,
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
