package com.medcore.clinical.encounter.write

import com.medcore.clinical.encounter.model.CancelReason
import com.medcore.clinical.encounter.model.EncounterClass
import com.medcore.clinical.encounter.model.EncounterStatus
import java.time.Instant
import java.util.UUID

/**
 * Immutable projection of a `clinical.encounter` row (Phase 4C.1 + 4C.5).
 *
 * Returned by every encounter handler so the same response mapper
 * serves all surfaces. Same discipline as 4A.2 `PatientSnapshot`.
 *
 * **4C.5 fields:**
 *   - [cancelledAt] / [cancelReason] — populated ⇔ status = CANCELLED.
 */
data class EncounterSnapshot(
    val id: UUID,
    val tenantId: UUID,
    val patientId: UUID,
    val status: EncounterStatus,
    val encounterClass: EncounterClass,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val cancelledAt: Instant?,
    val cancelReason: CancelReason?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID,
    val updatedBy: UUID,
    val rowVersion: Long,
)
