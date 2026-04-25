package com.medcore.clinical.allergy.write

import com.medcore.clinical.allergy.model.AllergySeverity
import com.medcore.clinical.allergy.model.AllergyStatus
import com.medcore.clinical.allergy.persistence.AllergyEntity
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Immutable post-handler view of a `clinical.allergy` row
 * (Phase 4E.1).
 *
 * Returned by every handler in the allergy domain (Add /
 * Update / Revoke / List). Mirrors `EncounterNoteSnapshot` /
 * `EncounterSnapshot` discipline — value-typed, no JPA
 * lifecycle leakage into controllers / DTOs / auditors.
 */
data class AllergySnapshot(
    val id: UUID,
    val tenantId: UUID,
    val patientId: UUID,
    val substanceText: String,
    val substanceCode: String?,
    val substanceSystem: String?,
    val severity: AllergySeverity,
    val status: AllergyStatus,
    val reactionText: String?,
    val onsetDate: LocalDate?,
    val recordedInEncounterId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID,
    val updatedBy: UUID,
    val rowVersion: Long,
) {
    companion object {
        fun from(entity: AllergyEntity): AllergySnapshot = AllergySnapshot(
            id = entity.id,
            tenantId = entity.tenantId,
            patientId = entity.patientId,
            substanceText = entity.substanceText,
            substanceCode = entity.substanceCode,
            substanceSystem = entity.substanceSystem,
            severity = entity.severity,
            status = entity.status,
            reactionText = entity.reactionText,
            onsetDate = entity.onsetDate,
            recordedInEncounterId = entity.recordedInEncounterId,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            createdBy = entity.createdBy,
            updatedBy = entity.updatedBy,
            rowVersion = entity.rowVersion,
        )
    }
}
