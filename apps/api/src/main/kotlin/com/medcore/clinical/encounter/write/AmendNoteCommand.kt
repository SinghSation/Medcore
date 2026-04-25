package com.medcore.clinical.encounter.write

import java.util.UUID

/**
 * Command for
 * `POST /api/v1/tenants/{slug}/encounters/{encounterId}/notes/{noteId}/amend`
 * (Phase 4D.6).
 *
 * Creates a NEW DRAFT note that references an existing SIGNED
 * note via `amends_id`. The original is never mutated — the
 * legal medical record stays intact (V20 trigger
 * `tr_clinical_encounter_note_immutable_once_signed` guarantees
 * this even against direct SQL).
 *
 * The amendment goes through the same DRAFT → SIGNED workflow as
 * a regular note (4D.5 sign endpoint). Reuse keeps the surface
 * area small.
 *
 * `body` is PHI; the validator enforces the same 1..20000 char
 * bound as `CreateEncounterNoteCommand`.
 *
 * ### Encounter-status discipline
 *
 * Unlike create-note, amend MUST work on closed encounters
 * (FINISHED / CANCELLED) — that's the entire point of
 * amendments: catching errors after the encounter has been
 * closed. The handler explicitly does NOT check encounter
 * status.
 */
data class AmendNoteCommand(
    val slug: String,
    val encounterId: UUID,
    val originalNoteId: UUID,
    val body: String,
)
