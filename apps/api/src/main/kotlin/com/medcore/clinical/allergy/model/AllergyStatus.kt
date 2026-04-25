package com.medcore.clinical.allergy.model

/**
 * Lifecycle state of a `clinical.allergy` row (Phase 4E.1).
 *
 * Closed enum — matches the DB CHECK constraint
 * `ck_clinical_allergy_status` added in V24. Adding a new state
 * requires a superseding ADR + migration.
 *
 * **Transitions (NORMATIVE — handler-enforced):**
 *
 *   - ACTIVE ↔ INACTIVE — bidirectional. A patient may have an
 *     allergy go INACTIVE (outgrown, resolved) and later become
 *     ACTIVE again (re-trigger / flare).
 *   - ACTIVE → ENTERED_IN_ERROR — retraction. The row was a
 *     mistake (wrong patient, wrong substance).
 *   - INACTIVE → ENTERED_IN_ERROR — retraction. Same as above
 *     for an entry that was deactivated before the mistake was
 *     noticed.
 *   - ENTERED_IN_ERROR is **terminal** — no transition out.
 *     Clinically, you cannot "un-error" a record. A subsequent
 *     mistake on a mistake (rare) is a new corrective entry,
 *     not a status revert.
 *
 * **DB enforcement:** the CHECK constraint enforces only the
 * closed-enum membership. Transition validity (terminal
 * ENTERED_IN_ERROR, etc.) lives in
 * [com.medcore.clinical.allergy.write.UpdateAllergyHandler]
 * because it depends on PRE-image state, which the application
 * owns. A stricter trigger-based enforcement is a carry-forward
 * if/when a non-handler write path becomes a credible threat.
 */
enum class AllergyStatus {
    ACTIVE,
    INACTIVE,
    ENTERED_IN_ERROR,
    ;

    /**
     * Status-priority for ADR-009 cursor pagination.
     *
     * Normative mapping (ADR-009 §2.5):
     *   ACTIVE = 0, INACTIVE = 1, RESOLVED = 2 (problems-only),
     *   ENTERED_IN_ERROR = 3.
     *
     * Allergies skip the `2` slot — the integer is uniform across
     * the platform so a `BucketedCursor.bucket` value has a
     * stable interpretation regardless of resource. The cursor's
     * `k` discriminator distinguishes allergy vs problem rows.
     *
     * The same mapping is encoded in
     * [com.medcore.clinical.allergy.persistence.AllergyRepository]'s
     * inline CASE expressions (JPQL) — keep them in sync.
     */
    val priority: Int
        get() = when (this) {
            ACTIVE -> 0
            INACTIVE -> 1
            ENTERED_IN_ERROR -> 3
        }
}
