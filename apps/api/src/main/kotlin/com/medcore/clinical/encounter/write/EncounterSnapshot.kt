package com.medcore.clinical.encounter.write

import com.medcore.clinical.encounter.model.EncounterClass
import com.medcore.clinical.encounter.model.EncounterStatus
import java.time.Instant
import java.util.UUID

/**
 * Immutable projection of a `clinical.encounter` row (Phase 4C.1).
 *
 * Returned by both the write handler ([StartEncounterHandler])
 * and the read handler
 * ([com.medcore.clinical.encounter.read.GetEncounterHandler]) so
 * the same response mapper serves both surfaces. Same discipline
 * as 4A.2 `PatientSnapshot`.
 */
data class EncounterSnapshot(
    val id: UUID,
    val tenantId: UUID,
    val patientId: UUID,
    val status: EncounterStatus,
    val encounterClass: EncounterClass,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID,
    val updatedBy: UUID,
    val rowVersion: Long,
)
