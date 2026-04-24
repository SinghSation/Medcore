package com.medcore.clinical.encounter.write

import com.medcore.clinical.encounter.model.CancelReason
import com.medcore.clinical.encounter.model.EncounterClass
import com.medcore.clinical.encounter.model.EncounterStatus
import com.medcore.clinical.encounter.persistence.EncounterEntity
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
 *
 * Use [from] to map from an entity — single source of truth.
 * Adding a new field requires one change here, not five parallel
 * copies across the encounter handlers (CodeRabbit review on PR #2
 * flagged the duplication this companion now eliminates).
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
) {
    companion object {
        fun from(entity: EncounterEntity): EncounterSnapshot = EncounterSnapshot(
            id = entity.id,
            tenantId = entity.tenantId,
            patientId = entity.patientId,
            status = entity.status,
            encounterClass = entity.encounterClass,
            startedAt = entity.startedAt,
            finishedAt = entity.finishedAt,
            cancelledAt = entity.cancelledAt,
            cancelReason = entity.cancelReason,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            createdBy = entity.createdBy,
            updatedBy = entity.updatedBy,
            rowVersion = entity.rowVersion,
        )
    }
}
