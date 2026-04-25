package com.medcore.clinical.problem.persistence

import com.medcore.clinical.problem.model.ProblemSeverity
import com.medcore.clinical.problem.model.ProblemStatus
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
 * JPA entity for `clinical.problem` (Phase 4E.2).
 *
 * Second longitudinal patient-level clinical row in Medcore
 * (after `AllergyEntity`). Lives on the patient and surfaces
 * on the patient detail page as a chart-context card — NOT a
 * banner: per the locked 4E.2 plan (Q6) the problem list is
 * not a clinical-safety surface like allergies.
 *
 * Conventions match `AllergyEntity` byte-for-byte except where
 * the schema diverges:
 *
 * - Regular mutable class (NOT a `data class`).
 * - Explicit `@Version` for optimistic concurrency.
 * - `tenant_id` denormalized for V25's RLS predicate.
 * - Kotlin `plugin.jpa` synthesises the no-arg constructor.
 *
 * ### Immutable post-create fields (NORMATIVE — locked Q7)
 *
 * - `condition_text` — clinical record discipline. To "change"
 *   the condition itself, mark the row ENTERED_IN_ERROR and
 *   create a new one. JPA `updatable = false` enforces at the
 *   ORM layer.
 * - `tenant_id`, `patient_id`, `recorded_in_encounter_id`,
 *   `created_at`, `created_by` — standard "minted on insert".
 *
 * ### Mutable fields
 *
 * - `severity` — clinical refinement; nullable per locked Q3.
 * - `onset_date`, `abatement_date` — clinical timeline can
 *   sharpen as more history surfaces.
 * - `status` — lifecycle transitions per [ProblemStatus]
 *   state machine. RESOLVED ≠ INACTIVE — see the KDoc on
 *   [ProblemStatus] for the normative distinction.
 * - `code_value`, `code_system` — reserved for 5A FHIR / 3M
 *   reference data; mutable so a future migration can backfill
 *   codes onto historical rows without a separate write
 *   surface.
 * - `updated_at`, `updated_by`, `row_version` — every UPDATE.
 *
 * ### `deleted_at`
 *
 * The platform PHI-table baseline column (V25 includes it from
 * day one per 4E.1 retro learnings). NOT mapped on this entity
 * because 4E.2 has no write path that populates it — clinical
 * lifecycle uses [ProblemStatus]. Future operational redaction
 * tooling will map it on its own dedicated entity / projection
 * when that slice lands.
 */
@Entity
@Table(name = "problem", schema = "clinical")
class ProblemEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID,

    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID,

    @Column(name = "patient_id", nullable = false, updatable = false)
    var patientId: UUID,

    @Column(name = "condition_text", nullable = false, updatable = false)
    var conditionText: String,

    @Column(name = "code_value")
    var codeValue: String? = null,

    @Column(name = "code_system")
    var codeSystem: String? = null,

    // Severity is NULLABLE per locked Q3 — no `nullable = false`.
    @Enumerated(EnumType.STRING)
    @Column(name = "severity")
    var severity: ProblemSeverity? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ProblemStatus = ProblemStatus.ACTIVE,

    @Column(name = "onset_date")
    var onsetDate: LocalDate? = null,

    @Column(name = "abatement_date")
    var abatementDate: LocalDate? = null,

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
     * column added in V29. Maintained entirely by PG from `status`
     * (see migration KDoc for the CASE mapping — RESOLVED ≠
     * INACTIVE is encoded here as 2 ≠ 1). Marked
     * `insertable = false, updatable = false` so JPA never tries
     * to write it; Hibernate hydrates it on every read.
     *
     * Application code never reads or writes this directly. It
     * exists on the entity solely so [ProblemRepository]'s JPQL
     * cursor-walk queries can `ORDER BY p.statusPriority` and
     * have PG resolve the sort directly from the V29 covering
     * index — see ADR-009 §2.5 + the V29 KDoc for the
     * raw-status-index-mismatch finding that motivated this.
     */
    @Column(name = "status_priority", insertable = false, updatable = false)
    var statusPriority: Short = 0
        protected set
}
