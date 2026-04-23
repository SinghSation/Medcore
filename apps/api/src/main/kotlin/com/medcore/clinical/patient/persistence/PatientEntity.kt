package com.medcore.clinical.patient.persistence

import com.medcore.clinical.patient.model.AdministrativeSex
import com.medcore.clinical.patient.model.MrnSource
import com.medcore.clinical.patient.model.PatientStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * JPA entity for `clinical.patient` (Phase 4A.1).
 *
 * Mirrors the established Medcore entity conventions:
 *
 * - Regular mutable class (NOT a `data class`) — generated
 *   `equals` / `hashCode` / `toString` are wrong for entities
 *   (equality on every field, PHI leak in log lines).
 * - Explicit `@Version` for optimistic concurrency.
 * - Protected no-arg constructor for JPA reflection.
 * - Never crosses module boundaries — callers receive projections
 *   (Phase 4A.2's PatientSnapshot for write paths, a read-side
 *   projection to be defined in 4A.2).
 *
 * ### FHIR alignment
 *
 * Column names map 1:1 to US Core Patient fields. [nameGiven] →
 * `Patient.name.given[0]`, [nameFamily] → `Patient.name.family`,
 * [administrativeSex] → `Patient.gender`, etc. The Phase 4A.4
 * `PatientFhirMapper` is a thin shape-to-FHIR-JSON translator;
 * the entity shape is the single source of truth.
 *
 * ### Enum mapping
 *
 * `@Enumerated(EnumType.STRING)` on [administrativeSex] stores
 * the Kotlin enum NAME (`MALE`, `FEMALE`, etc.) by default, but
 * the V14 CHECK constraint requires the FHIR WIRE values
 * (`male`, `female`, lowercase). [AdministrativeSex.wireValue]
 * carries the wire form; this entity overrides JPA mapping to
 * use it via the dedicated converter logic (currently handled by
 * mapping string directly; a typed AttributeConverter could land
 * with 4A.2 if verbose mapping accumulates).
 *
 * For simplicity in 4A.1, [administrativeSex] is stored as the
 * enum-name String but the CHECK constraint accepts
 * `'MALE'`/`'FEMALE'`/etc. Wait — see implementation note below.
 */
@Entity
@Table(name = "patient", schema = "clinical")
class PatientEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID,

    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID,

    @Column(name = "mrn", nullable = false, updatable = false)
    var mrn: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "mrn_source", nullable = false, updatable = false)
    var mrnSource: MrnSource,

    @Column(name = "name_given", nullable = false)
    var nameGiven: String,

    @Column(name = "name_family", nullable = false)
    var nameFamily: String,

    @Column(name = "name_middle")
    var nameMiddle: String? = null,

    @Column(name = "name_suffix")
    var nameSuffix: String? = null,

    @Column(name = "name_prefix")
    var namePrefix: String? = null,

    @Column(name = "preferred_name")
    var preferredName: String? = null,

    @Column(name = "birth_date", nullable = false)
    var birthDate: LocalDate,

    // Stored as the FHIR wire value (lowercase) directly; Kotlin
    // code converts via AdministrativeSex.fromWire / .wireValue
    // when reading/writing. See `patientAdministrativeSex` /
    // `setAdministrativeSex` helper methods.
    @Column(name = "administrative_sex", nullable = false)
    var administrativeSexWire: String,

    @Column(name = "sex_assigned_at_birth")
    var sexAssignedAtBirth: String? = null,

    @Column(name = "gender_identity_code")
    var genderIdentityCode: String? = null,

    @Column(name = "preferred_language")
    var preferredLanguage: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: PatientStatus = PatientStatus.ACTIVE,

    @Column(name = "merged_into_id")
    var mergedIntoId: UUID? = null,

    @Column(name = "merged_at")
    var mergedAt: Instant? = null,

    @Column(name = "merged_by")
    var mergedBy: UUID? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Column(name = "created_by", nullable = false, updatable = false)
    var createdBy: UUID,

    @Column(name = "updated_by", nullable = false)
    var updatedBy: UUID,

    @Version
    @Column(name = "row_version", nullable = false)
    var rowVersion: Long = 0,
) {
    /**
     * Typed accessor over the FHIR-wire-stored
     * [administrativeSexWire] column. Handlers read + write
     * `AdministrativeSex` via this property; the underlying
     * TEXT column stays FHIR-compliant without a JPA
     * `AttributeConverter` in 4A.1.
     */
    var administrativeSex: AdministrativeSex
        get() = AdministrativeSex.fromWire(administrativeSexWire)
        set(value) { administrativeSexWire = value.wireValue }

    @Suppress("unused") // JPA no-arg constructor.
    protected constructor() : this(
        id = UUID(0L, 0L),
        tenantId = UUID(0L, 0L),
        mrn = "",
        mrnSource = MrnSource.GENERATED,
        nameGiven = "",
        nameFamily = "",
        nameMiddle = null,
        nameSuffix = null,
        namePrefix = null,
        preferredName = null,
        birthDate = LocalDate.EPOCH,
        administrativeSexWire = AdministrativeSex.UNKNOWN.wireValue,
        sexAssignedAtBirth = null,
        genderIdentityCode = null,
        preferredLanguage = null,
        status = PatientStatus.ACTIVE,
        mergedIntoId = null,
        mergedAt = null,
        mergedBy = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        createdBy = UUID(0L, 0L),
        updatedBy = UUID(0L, 0L),
    )
}
