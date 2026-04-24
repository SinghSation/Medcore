package com.medcore.clinical.encounter.persistence

import com.medcore.clinical.encounter.model.EncounterNoteStatus
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
 * JPA entity for `clinical.encounter_note` (Phase 4D.1 + 4D.5).
 *
 * Append-only note tied to an encounter with a DRAFT → SIGNED
 * state machine (Phase 4D.5). Same entity conventions as
 * `EncounterEntity` / `PatientEntity`:
 *
 * - Regular mutable class (NOT a `data class`) — generated
 *   `equals` / `hashCode` / `toString` are wrong for entities
 *   (equality on every field; PHI-adjacent log lines).
 * - Explicit `@Version` for optimistic concurrency — exercised
 *   by the signing `UPDATE` in 4D.5.
 * - `tenant_id` denormalized on the row so the V19 RLS policy
 *   keys on a simple single-column predicate.
 * - Kotlin `plugin.jpa` synthesises the no-arg constructor.
 *
 * ### Signing invariants (DB-enforced, see V20)
 *
 * - `status = 'SIGNED'` ⇒ `signedAt` + `signedBy` are both set
 *   (CHECK `ck_clinical_encounter_note_signed_fields_coherent`).
 * - `status = 'DRAFT'`  ⇒ `signedAt` + `signedBy` are both NULL.
 * - Any UPDATE on a row whose PRE-image has `status = 'SIGNED'`
 *   is refused at the DB layer by the trigger
 *   `tr_clinical_encounter_note_immutable_once_signed`. The
 *   handler never attempts such updates; the trigger is
 *   defense-in-depth against direct-SQL or future-handler bugs.
 *
 * `amends_id` is declared for the future amendment workflow
 * (Phase 4D follow-on); in 4D.5 no write path ever sets it.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: EncounterNoteStatus = EncounterNoteStatus.DRAFT,

    @Column(name = "signed_at")
    var signedAt: Instant? = null,

    @Column(name = "signed_by")
    var signedBy: UUID? = null,

    @Column(name = "amends_id", updatable = false)
    var amendsId: UUID? = null,

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
