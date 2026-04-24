package com.medcore.clinical.encounter.write

import java.util.UUID

/**
 * Command for
 * `POST /api/v1/tenants/{slug}/encounters/{encounterId}/notes/{noteId}/sign`
 * (Phase 4D.5).
 *
 * State transition: `DRAFT → SIGNED` on the target note. No
 * request body — the action is encoded in the URL. The signer's
 * identity comes from the authenticated principal, not from the
 * command payload.
 */
data class SignEncounterNoteCommand(
    val slug: String,
    val encounterId: UUID,
    val noteId: UUID,
)
