package com.medcore.clinical.encounter.write

import java.util.UUID

/**
 * Command for
 * `POST /api/v1/tenants/{slug}/encounters/{encounterId}/notes`
 * (Phase 4D.1, VS1 Chunk E).
 *
 * Creates a new clinical note tied to an encounter. Append-only:
 * every call creates a new row. There is no update path in
 * 4D.1 — amendments are a Phase 4D follow-on slice.
 *
 * `body` is PHI. The validator enforces 1..20000 chars AFTER
 * trimming; the V19 CHECK constraint enforces the same bound
 * at the DB layer (defense in depth).
 */
data class CreateEncounterNoteCommand(
    val slug: String,
    val encounterId: UUID,
    val body: String,
)
