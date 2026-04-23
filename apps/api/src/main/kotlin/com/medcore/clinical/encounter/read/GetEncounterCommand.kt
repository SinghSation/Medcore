package com.medcore.clinical.encounter.read

import java.util.UUID

/**
 * Command for `GET /api/v1/tenants/{slug}/encounters/{encounterId}`
 * (Phase 4C.1, VS1 Chunk D).
 *
 * First encounter read in Medcore. Follows the 4A.4
 * `GetPatientCommand` pattern — immutable, carries only path
 * variables, no PHI in the command itself.
 */
data class GetEncounterCommand(
    val slug: String,
    val encounterId: UUID,
)
