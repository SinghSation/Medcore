package com.medcore.clinical.encounter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for `clinical.encounter_note` (Phase 4D.1, VS1 Chunk E).
 *
 * Append-only note tied to an encounter. Same entity
 * conventions as `EncounterEntity` / `PatientEntity`:
 *
 * - Regular mutable class (NOT a `data class`) — generated
 *   `equals` / `hashCode` / `toString` are wrong for entities
 *   (equality on every field; PHI-adjacent log lines).
 * - Explicit `@Version` for optimistic concurrency (unused at
 *   the controller layer in 4D.1 — append-only — but Hibernate
 *   relies on it during merge operations).
 * - `tenant_id` denormalized on the row so the V19 RLS policy
 *   keys on a simple single-column predicate.
 * - Kotlin `plugin.jpa` synthesises the no-arg constructor.
 */
@Entity
@Table(name = "encounter_note", schema = "clinical")
class EncounterNoteEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID,

    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID,

    @Column(name = "encounter_id", nullable = false, updatable = false)
    var encounterId: UUID,

    @Column(name = "body", nullable = false)
    var body: String,

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
