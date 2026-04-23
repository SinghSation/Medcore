package com.medcore.clinical.patient.persistence

import com.medcore.clinical.patient.model.PatientIdentifierType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for `clinical.patient_identifier` (Phase 4A.1).
 *
 * Satellite to [PatientEntity]. Stores typed external
 * identifiers (external MRNs, driver's licenses, insurance
 * member IDs, catch-all) so a patient's identity graph can
 * accommodate multiple identifiers without schema changes per
 * new integration.
 *
 * **`patient_id` is a bare UUID FK at the DB level**, but in
 * Kotlin this entity does NOT hold a JPA `@ManyToOne` reference
 * to [PatientEntity]. Rationale: keeps the satellite
 * independently loadable, avoids lazy-loading surprises, and
 * matches the Medcore convention from tenancy (membership →
 * tenant stored as bare UUID, not JPA association).
 *
 * **SSN deliberately absent from [PatientIdentifierType]** —
 * see that enum's KDoc for the compliance rationale.
 */
@Entity
@Table(name = "patient_identifier", schema = "clinical")
class PatientIdentifierEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID,

    @Column(name = "patient_id", nullable = false, updatable = false)
    var patientId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    var type: PatientIdentifierType,

    @Column(name = "issuer", nullable = false)
    var issuer: String,

    @Column(name = "value", nullable = false)
    var value: String,

    @Column(name = "valid_from")
    var validFrom: Instant? = null,

    @Column(name = "valid_to")
    var validTo: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Version
    @Column(name = "row_version", nullable = false)
    var rowVersion: Long = 0,
) {
    @Suppress("unused") // JPA no-arg constructor.
    protected constructor() : this(
        id = UUID(0L, 0L),
        patientId = UUID(0L, 0L),
        type = PatientIdentifierType.OTHER,
        issuer = "",
        value = "",
        validFrom = null,
        validTo = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
