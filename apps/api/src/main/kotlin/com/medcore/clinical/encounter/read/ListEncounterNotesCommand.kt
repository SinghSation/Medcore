package com.medcore.clinical.encounter.read

import com.medcore.platform.read.pagination.PageRequest
import java.util.UUID

/**
 * Command for
 * `GET /api/v1/tenants/{slug}/encounters/{encounterId}/notes`
 * (Phase 4D.1 — paginated as of platform-pagination chunk B,
 * ADR-009).
 *
 * Lists notes for an encounter newest-first. Cursor-based
 * pagination — see ADR-009 for the substrate. The `pageRequest`
 * field carries the caller-supplied `pageSize` + `cursor`
 * (defaults applied by [PageRequest.fromQueryParams] at the
 * controller boundary). Wire shape:
 *
 *   `?pageSize=50&cursor=<opaque>`
 *
 * First-call shape (no params): `pageSize = 50`, `cursor = null`.
 *
 * Sort axis: `(createdAt DESC, id DESC)`.
 *   - Cursor encodes the LAST row's `(createdAt, id)` per
 *     ADR-009 §2.2 (full sort tuple, not just last value).
 *   - Implementation: [com.medcore.platform.read.pagination.TimeCursor]
 *     with `ascending = false` and `k = "clinical.encounter_note.v1"`.
 */
data class ListEncounterNotesCommand(
    val slug: String,
    val encounterId: UUID,
    val pageRequest: PageRequest,
)
