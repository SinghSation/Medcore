package com.medcore.clinical.encounter.write

import com.medcore.clinical.encounter.model.EncounterNoteStatus
import java.time.Instant
import java.util.UUID

/**
 * Immutable projection of a `clinical.encounter_note` row
 * (Phase 4D.1 + 4D.5). Returned by every note write / read
 * handler so the response mapper serves all surfaces.
 *
 * **4D.5 fields:**
 *   - [status] — `DRAFT` or `SIGNED`.
 *   - [signedAt] / [signedBy] — populated ⇔ status = SIGNED.
 *   - [amendsId] — reserved for the future amendment workflow;
 *     always `null` in 4D.5.
 */
data class EncounterNoteSnapshot(
    val id: UUID,
    val tenantId: UUID,
    val encounterId: UUID,
    val body: String,
    val status: EncounterNoteStatus,
    val signedAt: Instant?,
    val signedBy: UUID?,
    val amendsId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID,
    val updatedBy: UUID,
    val rowVersion: Long,
)
