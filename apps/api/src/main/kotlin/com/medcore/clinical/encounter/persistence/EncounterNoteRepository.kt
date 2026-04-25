package com.medcore.clinical.encounter.persistence

import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * Repository for [EncounterNoteEntity] (Phase 4D.1, VS1 Chunk E;
 * paginated as of platform-pagination chunk B).
 *
 * `findFirstPage` and `findAfter` run under V19's
 * `p_encounter_note_select` RLS envelope — returned rows are
 * already scoped to the caller's tenant + active membership.
 *
 * ### Pagination — newest-first cursor walk
 *
 * Sort: `(createdAt DESC, id DESC)`. The cursor encodes the
 * LAST row of the previous page. The next page is fetched by
 * "find rows that come AFTER the cursor in this ordering"
 * which, for DESC ordering, is `(createdAt < ts_last) OR
 * (createdAt = ts_last AND id < id_last)`.
 *
 * Both queries fetch `limit` rows. The handler asks for
 * `pageSize + 1` to detect `hasNextPage` and trim the peek
 * row before returning.
 */
interface EncounterNoteRepository : JpaRepository<EncounterNoteEntity, UUID> {

    /**
     * First page of notes for an encounter, newest first.
     * `ORDER BY created_at DESC, id DESC` guarantees deterministic
     * order across ties.
     */
    @Query(
        """
        SELECT n FROM EncounterNoteEntity n
         WHERE n.encounterId = :encounterId
         ORDER BY n.createdAt DESC, n.id DESC
        """,
    )
    fun findFirstPage(
        @Param("encounterId") encounterId: UUID,
        limit: Limit,
    ): List<EncounterNoteEntity>

    /**
     * Cursor walk: rows strictly AFTER the given `(ts, id)` tuple
     * in the `(createdAt DESC, id DESC)` ordering. "After" for
     * a DESC sort means smaller-or-equal-timestamp-and-smaller-id.
     */
    @Query(
        """
        SELECT n FROM EncounterNoteEntity n
         WHERE n.encounterId = :encounterId
           AND ( n.createdAt < :ts
                 OR ( n.createdAt = :ts AND n.id < :id ) )
         ORDER BY n.createdAt DESC, n.id DESC
        """,
    )
    fun findAfter(
        @Param("encounterId") encounterId: UUID,
        @Param("ts") ts: Instant,
        @Param("id") id: UUID,
        limit: Limit,
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
