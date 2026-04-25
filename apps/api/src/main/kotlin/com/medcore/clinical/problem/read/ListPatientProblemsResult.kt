package com.medcore.clinical.problem.read

import com.medcore.clinical.problem.write.ProblemSnapshot

/**
 * Handler result for [ListPatientProblemsCommand] (Phase 4E.2).
 *
 * Un-paginated list — no `totalCount` / `limit` / `offset`
 * envelope fields, consistent with
 * [com.medcore.clinical.allergy.read.ListPatientAllergiesResult]
 * and the encounter / note list results. Adding pagination is
 * additive; existing clients ignoring new fields continue to
 * work.
 */
data class ListPatientProblemsResult(
    val items: List<ProblemSnapshot>,
)
