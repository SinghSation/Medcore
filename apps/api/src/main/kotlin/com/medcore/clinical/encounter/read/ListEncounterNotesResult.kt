package com.medcore.clinical.encounter.read

import com.medcore.clinical.encounter.write.EncounterNoteSnapshot

/**
 * Handler result for [ListEncounterNotesCommand]
 * (Phase 4D.1, VS1 Chunk E).
 *
 * Unlike `ListPatientsResult` (4B.1), this list has no
 * `totalCount` / `limit` / `offset` / `hasMore` because the
 * 4D.1 surface ships un-paginated (per scope decision). Adding
 * pagination is additive in a later slice — the response
 * envelope will expand; existing clients ignoring new fields
 * continue to work.
 */
data class ListEncounterNotesResult(
    val items: List<EncounterNoteSnapshot>,
)
