package com.medcore.clinical.encounter.read

import com.medcore.clinical.encounter.write.EncounterSnapshot
import com.medcore.platform.read.pagination.PageResponse

/**
 * Handler result for [ListPatientEncountersCommand]
 * (Phase 4C.3, paginated as of platform-pagination chunk C).
 *
 * Wire envelope: `{ items, pageInfo }` per ADR-009 §2.4. The
 * `typealias` keeps the substrate as the single source of
 * truth for the envelope shape — same posture as
 * [ListEncounterNotesResult].
 */
typealias ListPatientEncountersResult = PageResponse<EncounterSnapshot>
