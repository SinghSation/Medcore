package com.medcore.clinical.problem.write

import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.clinical.patient.write.Patchable
import com.medcore.clinical.problem.model.ProblemSeverity
import com.medcore.clinical.problem.model.ProblemStatus
import com.medcore.clinical.problem.persistence.ProblemEntity
import com.medcore.clinical.problem.persistence.ProblemRepository
import com.medcore.platform.write.WriteConflictException
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import org.springframework.stereotype.Component

/**
 * Handler for [UpdateProblemCommand] (Phase 4E.2).
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug (invariant — policy approved).
 * 2. Load the patient; verify `patient.tenantId == tenant.id`
 *    (cross-tenant probe → 404 — no existence leak).
 * 3. Load the problem; verify `problem.tenantId == tenant.id`
 *    AND `problem.patientId == patient.id` (either mismatch
 *    → 404, identical to "unknown problem").
 * 4. Compare `expectedRowVersion` to the loaded row's
 *    `row_version`. Mismatch → [WriteConflictException]
 *    `stale_row` (mirrors `UpdateAllergyHandler` discipline).
 * 5. Status-transition gate (NORMATIVE — locked Q8). The
 *    legal transition graph is enforced here, not at the DB
 *    layer, because it depends on PRE-image state:
 *    - Current = `ENTERED_IN_ERROR` AND any patch resolves to
 *      a real change → 409 `details.reason: problem_terminal`
 *      (terminal state).
 *    - Current = `ENTERED_IN_ERROR` AND every patch is no-op
 *      → return `kind = UPDATED, changed = false` (idempotent
 *      retry on a terminal row; suppressed audit).
 *    - Current = `RESOLVED` AND `status_to = INACTIVE` → 409
 *      `details.reason: problem_invalid_transition`.
 *      RESOLVED ≠ INACTIVE — see [ProblemStatus] KDoc;
 *      "downgrading" requires going through ACTIVE first
 *      (recurrence) or revoking via ENTERED_IN_ERROR.
 * 6. Apply patches via [Patchable.Absent] / `Set` / `Clear`
 *    semantics. No-op detection inside `apply()` — equal-
 *    value assignments do not mark the entity changed.
 * 7. Cross-field coherence check on the POST-state:
 *    `abatement_date >= onset_date` when both are non-null.
 *    This mirrors V25's CHECK constraint at the application
 *    layer for a clean 422; 409 is reserved for state /
 *    concurrency conflicts, not coherence violations.
 *    Refusal → [com.medcore.platform.write.WriteValidationException]
 *    `field=abatementDate, code=before_onset_date`.
 * 8. If nothing actually changed, return early with
 *    `changed = false`. The auditor suppresses emission.
 * 9. Otherwise stamp `updated_at` + `updated_by` and
 *    `saveAndFlush`. JPA `@Version` bumps `row_version`.
 * 10. Return [UpdateProblemOutcome] carrying the dispatch
 *     `kind` so the auditor knows whether to emit
 *     `CLINICAL_PROBLEM_UPDATED` (target ACTIVE / INACTIVE,
 *     covers recurrence too), `CLINICAL_PROBLEM_RESOLVED`
 *     (target RESOLVED), or `CLINICAL_PROBLEM_REVOKED`
 *     (target ENTERED_IN_ERROR).
 *
 * ### Why the three-way dispatch lives in the handler outcome
 *
 * The wire surface is one PATCH endpoint, but the audit surface
 * is three distinct actions per the locked plan. Putting the
 * dispatch `kind` on the handler outcome means the auditor
 * stays a thin event-shape mapper — no business logic in the
 * auditor about "was this a resolve / revoke?". This is a
 * deliberate extension of the 4E.1 two-way pattern to handle
 * the additional RESOLVED clinical-outcome action.
 *
 * ### RESOLVED ≠ INACTIVE (NORMATIVE)
 *
 * The handler enforces the distinction in three load-bearing
 * places:
 *   - The terminal-state guard treats RESOLVED → INACTIVE as
 *     an invalid transition (409, not silent acceptance).
 *   - The dispatch `kind` produces a different audit action
 *     for target=RESOLVED than target=INACTIVE.
 *   - `RESOLVED → ACTIVE` (recurrence) is allowed and routed
 *     to UPDATED with `status_from:RESOLVED|status_to:ACTIVE`,
 *     preserving the recurrence narrative in the audit slug.
 */
@Component
class UpdateProblemHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
    private val problemRepository: ProblemRepository,
    private val clock: Clock,
) {

    fun handle(
        command: UpdateProblemCommand,
        context: WriteContext,
    ): UpdateProblemOutcome {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val patient = patientRepository.findById(command.patientId).orElse(null)
            ?: throw EntityNotFoundException("patient not found: ${command.patientId}")
        if (patient.tenantId != tenant.id) {
            throw EntityNotFoundException("patient not found: ${command.patientId}")
        }

        val problem = problemRepository.findById(command.problemId).orElse(null)
            ?: throw EntityNotFoundException("problem not found: ${command.problemId}")
        if (problem.tenantId != tenant.id || problem.patientId != patient.id) {
            // Cross-tenant or cross-patient probe → 404 identical
            // to "unknown problem"; no existence leak.
            throw EntityNotFoundException("problem not found: ${command.problemId}")
        }

        if (problem.rowVersion != command.expectedRowVersion) {
            throw WriteConflictException("stale_row")
        }

        val priorStatus = problem.status

        // Pre-mutation: refuse RESOLVED → INACTIVE explicitly.
        // Done BEFORE applying patches so a RESOLVED row that
        // gets a status:INACTIVE patch (with or without other
        // fields) fails fast with the precise reason slug.
        if (priorStatus == ProblemStatus.RESOLVED && command.status is Patchable.Set) {
            if (command.status.value == ProblemStatus.INACTIVE) {
                throw WriteConflictException("problem_invalid_transition")
            }
        }

        // Snapshot the changes BEFORE mutating so we can compute
        // changedFields and the from→to status transition for
        // the audit slug. apply() returns the field name when it
        // actually mutated the entity (no-op suppression).
        val changedFields = mutableSetOf<String>()

        applySeverity(problem, command.severity)?.also { changedFields += it }
        applyOnsetDate(problem, command.onsetDate)?.also { changedFields += it }
        applyAbatementDate(problem, command.abatementDate)?.also { changedFields += it }
        applyStatus(problem, command.status)?.also { changedFields += it }

        val changed = changedFields.isNotEmpty()
        val newStatus = problem.status

        // Terminal-state guard: if the row was ENTERED_IN_ERROR
        // before this call AND the caller is trying to change
        // anything, refuse. An idempotent re-revoke (current ==
        // ENTERED_IN_ERROR, all patches resolve to no-op) falls
        // through as `changed = false` and is suppressed.
        if (priorStatus == ProblemStatus.ENTERED_IN_ERROR && changed) {
            throw WriteConflictException("problem_terminal")
        }

        // Cross-field POST-state coherence: abatement >= onset
        // when both are non-null. Matches V25's
        // ck_clinical_problem_abatement_after_onset.
        // Surfaces as 422 (validation), not 409 (state
        // conflict).
        val onset = problem.onsetDate
        val abatement = problem.abatementDate
        if (onset != null && abatement != null && abatement.isBefore(onset)) {
            throw com.medcore.platform.write.WriteValidationException(
                field = "abatementDate",
                code = "before_onset_date",
            )
        }

        if (!changed) {
            return UpdateProblemOutcome(
                snapshot = ProblemSnapshot.from(problem),
                changed = false,
                changedFields = emptySet(),
                priorStatus = priorStatus,
                kind = UpdateProblemOutcome.Kind.UPDATED,
            )
        }

        problem.updatedAt = Instant.now(clock)
        problem.updatedBy = context.principal.userId
        val saved = problemRepository.saveAndFlush(problem)

        // Three-way dispatch on TARGET status. RESOLVED ≠
        // INACTIVE — see [ProblemStatus] KDoc.
        val kind = when (newStatus) {
            ProblemStatus.RESOLVED -> UpdateProblemOutcome.Kind.RESOLVED
            ProblemStatus.ENTERED_IN_ERROR -> UpdateProblemOutcome.Kind.REVOKED
            ProblemStatus.ACTIVE,
            ProblemStatus.INACTIVE -> UpdateProblemOutcome.Kind.UPDATED
        }

        return UpdateProblemOutcome(
            snapshot = ProblemSnapshot.from(saved),
            changed = true,
            changedFields = changedFields.toSet(),
            priorStatus = priorStatus,
            kind = kind,
        )
    }

    /**
     * Returns the field name if a change was applied; null on
     * no-op.
     */
    private fun applySeverity(
        entity: ProblemEntity,
        patch: Patchable<ProblemSeverity>,
    ): String? = when (patch) {
        Patchable.Absent -> null
        Patchable.Clear -> if (entity.severity != null) {
            entity.severity = null
            "severity"
        } else null
        is Patchable.Set -> if (patch.value != entity.severity) {
            entity.severity = patch.value
            "severity"
        } else null
    }

    private fun applyOnsetDate(
        entity: ProblemEntity,
        patch: Patchable<LocalDate>,
    ): String? = when (patch) {
        Patchable.Absent -> null
        Patchable.Clear -> if (entity.onsetDate != null) {
            entity.onsetDate = null
            "onsetDate"
        } else null
        is Patchable.Set -> if (patch.value != entity.onsetDate) {
            entity.onsetDate = patch.value
            "onsetDate"
        } else null
    }

    private fun applyAbatementDate(
        entity: ProblemEntity,
        patch: Patchable<LocalDate>,
    ): String? = when (patch) {
        Patchable.Absent -> null
        Patchable.Clear -> if (entity.abatementDate != null) {
            entity.abatementDate = null
            "abatementDate"
        } else null
        is Patchable.Set -> if (patch.value != entity.abatementDate) {
            entity.abatementDate = patch.value
            "abatementDate"
        } else null
    }

    private fun applyStatus(
        entity: ProblemEntity,
        patch: Patchable<ProblemStatus>,
    ): String? = when (patch) {
        Patchable.Absent -> null
        Patchable.Clear -> null // refused by validator
        is Patchable.Set -> if (patch.value != entity.status) {
            entity.status = patch.value
            "status"
        } else null
    }
}

/**
 * Handler outcome for problem updates.
 *
 * Carries:
 *   - the snapshot for the response body
 *   - the no-op flag for audit suppression
 *   - the changed-field set + prior-status for the audit
 *     reason slug
 *   - the dispatch `kind` the auditor uses to choose between
 *     [com.medcore.platform.audit.AuditAction.CLINICAL_PROBLEM_UPDATED]
 *     (target ACTIVE / INACTIVE — covers recurrence
 *     RESOLVED → ACTIVE),
 *     [com.medcore.platform.audit.AuditAction.CLINICAL_PROBLEM_RESOLVED]
 *     (target RESOLVED), or
 *     [com.medcore.platform.audit.AuditAction.CLINICAL_PROBLEM_REVOKED]
 *     (target ENTERED_IN_ERROR).
 *
 * Three-way (vs the 4E.1 two-way) because problems have an
 * additional RESOLVED clinical-outcome action that MUST stay
 * distinct from INACTIVE per the load-bearing RESOLVED ≠
 * INACTIVE invariant.
 */
data class UpdateProblemOutcome(
    val snapshot: ProblemSnapshot,
    val changed: Boolean,
    val changedFields: Set<String>,
    val priorStatus: ProblemStatus,
    val kind: Kind,
) {
    enum class Kind {
        /** Target status ∈ {ACTIVE, INACTIVE}. Includes recurrence (RESOLVED → ACTIVE). */
        UPDATED,
        /** Target status = RESOLVED. Distinct clinical-outcome action. */
        RESOLVED,
        /** Target status = ENTERED_IN_ERROR. Soft-delete retraction. */
        REVOKED,
    }
}
