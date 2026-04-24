package com.medcore.clinical.encounter.write

import java.util.UUID

/**
 * Command for
 * `POST /api/v1/tenants/{slug}/encounters/{encounterId}/finish`
 * (Phase 4C.5).
 *
 * State transition: `IN_PROGRESS → FINISHED`. Refused when the
 * encounter has 0 signed notes (invariant — see
 * [FinishEncounterHandler]).
 *
 * No request body — the action is encoded in the URL. The
 * finisher's identity is the authenticated principal.
 */
data class FinishEncounterCommand(
    val slug: String,
    val encounterId: UUID,
)
