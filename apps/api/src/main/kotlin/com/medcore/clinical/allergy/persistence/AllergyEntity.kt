package com.medcore.clinical.allergy.persistence

import com.medcore.clinical.allergy.model.AllergySeverity
import com.medcore.clinical.allergy.model.AllergyStatus
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
 * JPA entity for `clinical.allergy` (Phase 4E.1).
 *
 * First longitudinal patient-level clinical row in Medcore —
 * lives on the patient, not the encounter, and surfaces in a
 * banner across every encounter view.
 *
 * Conventions match `EncounterEntity` / `EncounterNoteEntity` /
 * `PatientEntity`:
 *
 * - Regular mutable class (NOT a `data class`) — generated
 *   `equals` / `hashCode` / `toString` are wrong for entities
 *   (equality on every field; PHI-adjacent log lines).
 * - Explicit `@Version` for optimistic concurrency — exercised
 *   on every PATCH (severity / reaction_text / onset_date /
 *   status update).
 * - `tenant_id` denormalized for V24's RLS predicate.
 * - Kotlin `plugin.jpa` synthesises the no-arg constructor.
 *
 * ### Immutable post-create fields (NORMATIVE — locked Q2)
 *
 * - `substance_text` — clinical record discipline. To "change"
 *   the substance, mark the row ENTERED_IN_ERROR and create a
 *   new one. JPA `updatable = false` enforces at the ORM layer.
 * - `tenant_id`, `patient_id`, `recorded_in_encounter_id`,
 *   `created_at`, `created_by` — standard "minted on insert"
 *   pattern.
 *
 * ### Mutable fields
 *
 * - `severity`, `reaction_text`, `onset_date` — clinical
 *   refinements over time.
 * - `status` — lifecycle transitions per
 *   [AllergyStatus] state machine.
 * - `substance_code`, `substance_system` — reserved for 5A FHIR
 *   coding; mutable so a future migration can backfill codes
 *   onto historical rows without a separate write surface.
 * - `updated_at`, `updated_by`, `row_version` — every UPDATE.
 */
@Entity
@Table(name = "allergy", schema = "clinical")
class AllergyEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID,

    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID,

    @Column(name = "patient_id", nullable = false, updatable = false)
    var patientId: UUID,

    @Column(name = "substance_text", nullable = false, updatable = false)
    var substanceText: String,

    @Column(name = "substance_code")
    var substanceCode: String? = null,

    @Column(name = "substance_system")
    var substanceSystem: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    var severity: AllergySeverity,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: AllergyStatus = AllergyStatus.ACTIVE,

    @Column(name = "reaction_text")
    var reactionText: String? = null,

    @Column(name = "onset_date")
    var onsetDate: LocalDate? = null,

    @Column(name = "recorded_in_encounter_id", updatable = false)
    var recordedInEncounterId: UUID? = null,

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
     * Mirror of the `status_priority` GENERATED ALWAYS AS STORED
     * column added in V28. Maintained entirely by PG from `status`
     * (see migration KDoc for the CASE mapping). Marked
     * `insertable = false, updatable = false` so JPA never tries
     * to write it; Hibernate hydrates it on every read.
     *
     * Application code never reads or writes this directly. It
     * exists on the entity solely so [AllergyRepository]'s JPQL
     * cursor-walk queries can `ORDER BY a.statusPriority` and
     * have PG resolve the sort directly from the V28 covering
     * index — see ADR-009 §2.5 + the V28 KDoc for the
     * raw-status-index-mismatch finding that motivated this.
     */
    @Column(name = "status_priority", insertable = false, updatable = false)
    var statusPriority: Short = 0
        protected set
}
