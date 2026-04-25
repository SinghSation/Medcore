package com.medcore.clinical.problem.read

import com.medcore.clinical.problem.write.ProblemSnapshot
import com.medcore.platform.read.pagination.PageResponse

/**
 * Handler result for [ListPatientProblemsCommand]
 * (Phase 4E.2, paginated as of platform-pagination chunk E).
 *
 * Wire envelope: `{ items, pageInfo }` per ADR-009 §2.4.
 */
typealias ListPatientProblemsResult = PageResponse<ProblemSnapshot>
