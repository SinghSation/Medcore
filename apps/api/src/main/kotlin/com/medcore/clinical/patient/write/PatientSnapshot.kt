package com.medcore.clinical.patient.write

import com.medcore.clinical.patient.model.AdministrativeSex
import com.medcore.clinical.patient.model.MrnSource
import com.medcore.clinical.patient.model.PatientStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Immutable snapshot of a `clinical.patient` row returned to
 * controllers (Phase 4A.2).
 *
 * ### Why a snapshot instead of the entity
 *
 * JPA entity instances are tied to a persistence context. Returning
 * `PatientEntity` across module boundaries risks:
 *
 * - accidental lazy-loading triggering additional queries at
 *   serialisation time;
 * - `equals` / `hashCode` / `toString` drift if an entity is ever
 *   made a `data class` by accident (PHI leak in log lines);
 * - controllers reaching into JPA-specific state.
 *
 * Snapshots are plain `data class` carriers of the fields the
 * HTTP layer needs. Controllers build `PatientResponse` DTOs from
 * them directly.
 *
 * ### PHI discipline
 *
 * All name fields, birth_date, and demographic fields are PHI per
 * 45 CFR §164.514(b). The snapshot MUST NOT appear in any log line,
 * MDC key, tracing attribute, or error-response body. Enforcement
 * via `PatientLogPhiLeakageTest`.
 */
data class PatientSnapshot(
    val id: UUID,
    val tenantId: UUID,
    val mrn: String,
    val mrnSource: MrnSource,

    val nameGiven: String,
    val nameFamily: String,
    val nameMiddle: String?,
    val nameSuffix: String?,
    val namePrefix: String?,
    val preferredName: String?,

    val birthDate: LocalDate,
    val administrativeSex: AdministrativeSex,
    val sexAssignedAtBirth: String?,
    val genderIdentityCode: String?,
    val preferredLanguage: String?,

    val status: PatientStatus,

    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID,
    val updatedBy: UUID,
    val rowVersion: Long,
)
