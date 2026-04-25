package com.medcore.clinical.encounter.read

import com.medcore.clinical.encounter.write.EncounterNoteSnapshot
import com.medcore.platform.read.pagination.PageResponse

/**
 * Handler result for [ListEncounterNotesCommand]
 * (Phase 4D.1, paginated as of platform-pagination chunk B).
 *
 * Wire envelope: `{ items, pageInfo }` per ADR-009 §2.4. No
 * `totalCount` (FHIR Bundle posture; computing total per page
 * is a redundant `COUNT(*)`).
 *
 * Defining this as a typealias to `PageResponse<T>` (rather
 * than re-declaring its fields) keeps the substrate the single
 * source of truth; future evolution of `PageResponse<T>` (e.g.,
 * additive metadata) propagates automatically.
 */
typealias ListEncounterNotesResult = PageResponse<EncounterNoteSnapshot>
