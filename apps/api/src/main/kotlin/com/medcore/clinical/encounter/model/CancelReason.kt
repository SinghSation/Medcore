package com.medcore.clinical.encounter.model

/**
 * Closed enum of cancel-reason codes for
 * `clinical.encounter.cancel_reason` (Phase 4C.5).
 *
 * Mirrors the V21 CHECK constraint
 * `ck_clinical_encounter_cancel_reason`. Adding a new value
 * requires: enum entry + CHECK update + handler validator
 * update + test update + review-pack callout. Renaming /
 * removing a value is a breaking wire-contract change —
 * handle via superseding ADR (Rule 07 forward-only).
 *
 * **PHI posture.** These tokens are closed-enum and safe to
 * include in audit reason slugs
 * (`intent:clinical.encounter.cancel|reason:NO_SHOW`). Free-
 * text cancel reasons are deliberately NOT introduced for
 * 4C.5 — text fields are a slippery slope to logging PHI via
 * "just this one field."
 *
 * **Semantics (documented simplification):**
 *
 *   - `NO_SHOW` — patient did not arrive for the scheduled
 *     encounter.
 *   - `PATIENT_DECLINED` — patient arrived but declined to
 *     proceed, or withdrew consent mid-visit.
 *   - `SCHEDULING_ERROR` — encounter created in error by the
 *     clinician or front-desk (wrong patient, duplicate
 *     appointment). Distinct from `entered-in-error` (a future
 *     terminal state for clinically-erroneous encounters).
 *   - `OTHER` — catches edge cases for MVP. A pilot clinic
 *     showing frequent `OTHER` use is a signal to split it
 *     into more specific codes (Rule 07 forward-only
 *     expansion).
 */
enum class CancelReason {
    NO_SHOW,
    PATIENT_DECLINED,
    SCHEDULING_ERROR,
    OTHER,
}
