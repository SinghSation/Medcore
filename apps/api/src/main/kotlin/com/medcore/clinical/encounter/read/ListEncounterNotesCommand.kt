package com.medcore.clinical.encounter.read

import java.util.UUID

/**
 * Command for
 * `GET /api/v1/tenants/{slug}/encounters/{encounterId}/notes`
 * (Phase 4D.1, VS1 Chunk E).
 *
 * Lists all notes for an encounter, newest first. No pagination
 * in 4D.1 — bounded in practice (VS1 demo writes ≤ a few per
 * encounter; real clinical workflows rarely exceed dozens per
 * encounter). Cursor-based pagination is a later slice if
 * needed.
 */
data class ListEncounterNotesCommand(
    val slug: String,
    val encounterId: UUID,
)
