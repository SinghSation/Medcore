package com.medcore.clinical.encounter.persistence

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Repository for [EncounterNoteEntity] (Phase 4D.1, VS1 Chunk E).
 *
 * Query surface kept minimal — the handler layer's two concrete
 * needs only. ArchUnit Rule 1 access perimeter matches the
 * `EncounterRepository` pattern.
 *
 * `findByEncounterIdOrdered` runs under V19's `p_encounter_note_select`
 * RLS envelope — the returned rows are already scoped to the
 * caller's visible tenant + active membership.
 */
interface EncounterNoteRepository : JpaRepository<EncounterNoteEntity, UUID> {

    /**
     * List notes for an encounter, newest first. `ORDER BY
     * created_at DESC, id DESC` guarantees deterministic order
     * even when two notes share a microsecond-equal timestamp.
     */
    @Query(
        """
        SELECT n FROM EncounterNoteEntity n
         WHERE n.encounterId = :encounterId
         ORDER BY n.createdAt DESC, n.id DESC
        """,
    )
    fun findByEncounterIdOrdered(
        @Param("encounterId") encounterId: UUID,
    ): List<EncounterNoteEntity>

    /**
     * Count of SIGNED notes on an encounter (Phase 4C.5). The
     * FINISH handler uses this to enforce the
     * "encounter must have ≥ 1 signed note before it can be
     * FINISHED" invariant — the single product decision this
     * whole 4D.5 → 4C.5 sequencing was built for.
     *
     * Executes under V19's `p_encounter_note_select` RLS
     * envelope. The caller's tenant-membership + RLS GUCs
     * guarantee we only count rows visible to them.
     */
    @Query(
        """
        SELECT COUNT(n) FROM EncounterNoteEntity n
         WHERE n.encounterId = :encounterId
           AND n.status = com.medcore.clinical.encounter.model.EncounterNoteStatus.SIGNED
        """,
    )
    fun countSignedByEncounterId(
        @Param("encounterId") encounterId: UUID,
    ): Long
}
