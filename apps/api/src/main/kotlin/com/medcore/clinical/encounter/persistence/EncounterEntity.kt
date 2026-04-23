package com.medcore.clinical.encounter.persistence

import com.medcore.clinical.encounter.model.EncounterClass
import com.medcore.clinical.encounter.model.EncounterStatus
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
 * JPA entity for `clinical.encounter` (Phase 4C.1, VS1 Chunk D).
 *
 * Mirrors the Medcore entity conventions established by 4A.1's
 * `PatientEntity`:
 *
 * - Regular mutable class (NOT a `data class`) — generated
 *   `equals` / `hashCode` / `toString` are wrong for entities
 *   (equality on every field; PHI-adjacency in log lines).
 * - Explicit `@Version` for optimistic concurrency.
 * - No cross-module object references — callers receive
 *   [com.medcore.clinical.encounter.write.EncounterSnapshot]
 *   projections.
 * - Kotlin `plugin.jpa` synthesises the no-arg ctor.
 */
@Entity
@Table(name = "encounter", schema = "clinical")
class EncounterEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID,

    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID,

    @Column(name = "patient_id", nullable = false, updatable = false)
    var patientId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: EncounterStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "encounter_class", nullable = false, updatable = false)
    var encounterClass: EncounterClass,

    @Column(name = "started_at")
    var startedAt: Instant? = null,

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,

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
)
