package com.medcore.clinical.encounter.write

import com.medcore.clinical.encounter.model.CancelReason
import java.util.UUID

/**
 * Command for
 * `POST /api/v1/tenants/{slug}/encounters/{encounterId}/cancel`
 * (Phase 4C.5).
 *
 * State transition: `IN_PROGRESS → CANCELLED`. Refused when the
 * encounter is already closed (FINISHED or CANCELLED).
 *
 * `cancelReason` is a closed-enum value drawn from
 * [CancelReason]. Free-text reasons are deliberately NOT in
 * scope — reason codes avoid the PHI-in-audit-slug risk.
 */
data class CancelEncounterCommand(
    val slug: String,
    val encounterId: UUID,
    val cancelReason: CancelReason,
)
