package com.medcore.clinical.allergy.read

import com.medcore.clinical.allergy.write.AllergySnapshot

/**
 * Handler result for [ListPatientAllergiesCommand] (Phase 4E.1).
 *
 * Un-paginated list — no `totalCount` / `limit` / `offset`
 * envelope fields, consistent with
 * [com.medcore.clinical.encounter.read.ListPatientEncountersResult]
 * and [com.medcore.clinical.encounter.read.ListEncounterNotesResult].
 * Adding pagination is additive; existing clients ignoring new
 * fields continue to work.
 */
data class ListPatientAllergiesResult(
    val items: List<AllergySnapshot>,
)
