package com.medcore.clinical.encounter.read

import com.medcore.clinical.encounter.persistence.EncounterNoteEntity
import com.medcore.clinical.encounter.persistence.EncounterNoteRepository
import com.medcore.clinical.encounter.persistence.EncounterRepository
import com.medcore.clinical.encounter.write.EncounterNoteSnapshot
import com.medcore.platform.read.pagination.CursorCodec
import com.medcore.platform.read.pagination.PageResponse
import com.medcore.platform.read.pagination.TimeCursor
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Component

/**
 * Handler for [ListEncounterNotesCommand] (Phase 4D.1,
 * VS1 Chunk E; paginated as of platform-pagination chunk B,
 * ADR-009).
 *
 * Runs inside [com.medcore.platform.read.ReadGate]'s transaction
 * with both RLS GUCs set by `PhiRlsTxHook`.
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug.
 * 2. Load parent encounter; verify `encounter.tenantId == tenant.id`
 *    (cross-tenant probe → 404 — no existence leak).
 * 3. Decode the optional cursor (`null` → first page; non-null →
 *    next page after the encoded `(createdAt, id)` tuple).
 *    Malformed cursor → [com.medcore.platform.read.pagination.CursorMalformedException]
 *    → 422 `cursor|malformed` via the global exception handler.
 * 4. Fetch `pageSize + 1` rows from the repository (the +1 is the
 *    "peek-ahead" that detects `hasNextPage`).
 * 5. Hand the over-fetched list to
 *    [PageResponse.fromFetchedPlusOne] which trims, computes
 *    `hasNextPage`, and constructs `nextCursor` from the LAST
 *    row of the trimmed page (NOT the peek-ahead row).
 *
 * ### Cursor shape
 *
 * `TimeCursor(k = "clinical.encounter_note.v1", ts, id, ascending = false)`.
 * The `ascending = false` flag documents the DESC sort axis —
 * useful for forensic / replay tools that decode cursors
 * without joining the schema.
 */
@Component
class ListEncounterNotesHandler(
    private val tenantRepository: TenantRepository,
    private val encounterRepository: EncounterRepository,
    private val encounterNoteRepository: EncounterNoteRepository,
) {

    fun handle(
        command: ListEncounterNotesCommand,
        @Suppress("UNUSED_PARAMETER") context: WriteContext,
    ): ListEncounterNotesResult {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val encounter = encounterRepository.findById(command.encounterId).orElse(null)
            ?: throw EntityNotFoundException("encounter not found: ${command.encounterId}")
        if (encounter.tenantId != tenant.id) {
            throw EntityNotFoundException("encounter not found: ${command.encounterId}")
        }

        // +1 to detect hasNextPage — see ADR-009 §2.4 + the
        // PageResponse.fromFetchedPlusOne contract.
        val pageSize = command.pageRequest.pageSize
        val limit = Limit.of(pageSize + 1)

        val rawCursor = command.pageRequest.cursor
        val rows: List<EncounterNoteEntity> = if (rawCursor == null) {
            encounterNoteRepository.findFirstPage(encounter.id, limit)
        } else {
            val map = CursorCodec.decodeMap(rawCursor)
            val cursor = TimeCursor.fromMap(
                map = map,
                expectedKey = CURSOR_K,
            )
            encounterNoteRepository.findAfter(
                encounterId = encounter.id,
                ts = cursor.ts,
                id = cursor.id,
                limit = limit,
            )
        }

        return PageResponse.fromFetchedPlusOne(
            fetchedPlusOne = rows.map { it.toSnapshot() },
            pageSize = pageSize,
        ) { last ->
            TimeCursor(
                k = CURSOR_K,
                ts = last.createdAt,
                id = last.id,
                ascending = false,
            )
        }
    }

    private fun EncounterNoteEntity.toSnapshot(): EncounterNoteSnapshot =
        EncounterNoteSnapshot(
            id = id,
            tenantId = tenantId,
            encounterId = encounterId,
            body = body,
            status = status,
            signedAt = signedAt,
            signedBy = signedBy,
            amendsId = amendsId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            createdBy = createdBy,
            updatedBy = updatedBy,
            rowVersion = rowVersion,
        )

    private companion object {
        /** Cursor schema discriminator; bump to `.v2` if the sort axis or fields change. */
        const val CURSOR_K: String = "clinical.encounter_note.v1"
    }
}
