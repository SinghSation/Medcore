package com.medcore.clinical.problem.model

/**
 * Lifecycle state of a `clinical.problem` row (Phase 4E.2).
 *
 * Closed enum — matches the DB CHECK constraint
 * `ck_clinical_problem_status` added in V25. Adding a new state
 * requires a superseding ADR + migration.
 *
 * ### RESOLVED ≠ INACTIVE (NORMATIVE)
 *
 * This is the single most important semantic distinction in
 * 4E.2 and MUST be preserved across every layer (audit, UI,
 * tests, analytics):
 *
 *   - **INACTIVE** — the condition exists but is currently
 *     dormant. Chronic conditions that flare and quiesce
 *     (asthma, eczema, recurrent migraine) live here when
 *     not currently symptomatic. The diagnosis remains
 *     valid; the patient simply isn't symptomatic now.
 *   - **RESOLVED** — the condition no longer exists. A cure,
 *     a successful one-off treatment, a recovered illness.
 *     Still recorded for medical history (it happened) but
 *     NOT a current problem.
 *
 * A clinician asked "is this an active problem?" must say no
 * for both. A clinician asked "could this come back?" must say
 * "yes, possibly" for INACTIVE and "no" for RESOLVED. Treating
 * the two as interchangeable would corrupt:
 *   - clinical history queries
 *   - quality-measure reporting
 *   - patient-facing summaries
 *   - the dedicated [com.medcore.platform.audit.AuditAction.CLINICAL_PROBLEM_RESOLVED]
 *     action which would be reduced to a meaningless flag.
 *
 * ### Transitions (NORMATIVE — handler-enforced)
 *
 *   - `ACTIVE ↔ INACTIVE` — bidirectional; chronic flare/quiesce.
 *   - `ACTIVE → RESOLVED` — clinical-outcome cure path.
 *   - `RESOLVED → ACTIVE` — recurrence (a "resolved" problem
 *     can come back; the database supports the transition,
 *     the clinician chooses whether it is appropriate).
 *   - `INACTIVE → RESOLVED` — re-classification of a dormant
 *     condition as fully resolved. Distinct narrative from
 *     `ACTIVE → RESOLVED`; the audit row's `prior_status`
 *     token preserves it.
 *   - `RESOLVED → INACTIVE` — **disallowed**. RESOLVED is a
 *     stronger statement than INACTIVE; "downgrading" requires
 *     the clinician to first transition through ACTIVE
 *     (recurrence) or revoke the resolution as
 *     ENTERED_IN_ERROR.
 *   - Any → `ENTERED_IN_ERROR` — retraction. The record was
 *     a mistake.
 *   - `ENTERED_IN_ERROR` is **terminal** — no transition out.
 *     Same discipline as [com.medcore.clinical.allergy.model.AllergyStatus].
 *
 * **DB enforcement:** the CHECK constraint enforces only the
 * closed-enum membership. Transition validity (terminal
 * ENTERED_IN_ERROR, RESOLVED → INACTIVE refusal, etc.) lives
 * in `com.medcore.clinical.problem.write.UpdateProblemHandler`
 * because it depends on PRE-image state, which the application
 * owns.
 */
enum class ProblemStatus {
    ACTIVE,
    INACTIVE,
    RESOLVED,
    ENTERED_IN_ERROR,
}
