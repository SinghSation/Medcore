package com.medcore.clinical.encounter.write

import java.time.Instant
import java.util.UUID

/**
 * Immutable projection of a `clinical.encounter_note` row
 * (Phase 4D.1, VS1 Chunk E). Returned by both write and read
 * handlers so the response mapper serves both surfaces.
 */
data class EncounterNoteSnapshot(
    val id: UUID,
    val tenantId: UUID,
    val encounterId: UUID,
    val body: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID,
    val updatedBy: UUID,
    val rowVersion: Long,
)
