package com.medcore.clinical.problem.write

import com.medcore.clinical.problem.model.ProblemSeverity
import com.medcore.clinical.problem.model.ProblemStatus
import com.medcore.clinical.problem.persistence.ProblemEntity
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Immutable post-handler view of a `clinical.problem` row
 * (Phase 4E.2).
 *
 * Returned by every handler in the problem domain (Add /
 * Update / List). Mirrors `AllergySnapshot` discipline —
 * value-typed, no JPA lifecycle leakage into controllers,
 * DTOs, or auditors.
 *
 * Severity is `ProblemSeverity?` (nullable) per locked Q3 of
 * the 4E.2 plan. Other nullable clinical fields
 * (`onsetDate`, `abatementDate`, `codeValue`, `codeSystem`,
 * `recordedInEncounterId`) follow the same pattern.
 */
data class ProblemSnapshot(
    val id: UUID,
    val tenantId: UUID,
    val patientId: UUID,
    val conditionText: String,
    val codeValue: String?,
    val codeSystem: String?,
    val severity: ProblemSeverity?,
    val status: ProblemStatus,
    val onsetDate: LocalDate?,
    val abatementDate: LocalDate?,
    val recordedInEncounterId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID,
    val updatedBy: UUID,
    val rowVersion: Long,
) {
    companion object {
        fun from(entity: ProblemEntity): ProblemSnapshot = ProblemSnapshot(
            id = entity.id,
            tenantId = entity.tenantId,
            patientId = entity.patientId,
            conditionText = entity.conditionText,
            codeValue = entity.codeValue,
            codeSystem = entity.codeSystem,
            severity = entity.severity,
            status = entity.status,
            onsetDate = entity.onsetDate,
            abatementDate = entity.abatementDate,
            recordedInEncounterId = entity.recordedInEncounterId,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            createdBy = entity.createdBy,
            updatedBy = entity.updatedBy,
            rowVersion = entity.rowVersion,
        )
    }
}
