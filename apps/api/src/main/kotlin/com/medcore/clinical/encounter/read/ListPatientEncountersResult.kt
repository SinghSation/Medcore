package com.medcore.clinical.encounter.read

import com.medcore.clinical.encounter.write.EncounterSnapshot

/**
 * Handler result for [ListPatientEncountersCommand] (Phase 4C.3).
 *
 * Un-paginated list — no `totalCount` / `limit` / `offset`
 * envelope fields, consistent with
 * [ListEncounterNotesResult]. Adding pagination is additive in a
 * later slice; existing clients ignoring new fields continue to
 * work.
 */
data class ListPatientEncountersResult(
    val items: List<EncounterSnapshot>,
)
