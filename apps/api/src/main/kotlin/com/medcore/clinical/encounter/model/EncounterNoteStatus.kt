package com.medcore.clinical.encounter.model

/**
 * Lifecycle state of a `clinical.encounter_note` row
 * (Phase 4D.5).
 *
 * Closed enum — matches the DB `CHECK` constraint
 * `ck_clinical_encounter_note_status` added in V20. Adding a
 * new state requires a superseding ADR + migration.
 *
 * **Transitions (NORMATIVE):**
 *
 *   - DRAFT → SIGNED (once; via
 *     [com.medcore.clinical.encounter.write.SignEncounterNoteCommand])
 *
 * **Refused transitions:**
 *
 *   - SIGNED → DRAFT — un-signing is not a product capability.
 *   - SIGNED → SIGNED — re-signing returns 409 `resource.conflict`
 *     with `reason: note_already_signed`.
 *
 * Immutability of signed rows is enforced:
 *   1. At the handler layer ([com.medcore.clinical.encounter.write.SignEncounterNoteHandler]).
 *   2. At the DB layer via the V20 trigger
 *      `tr_clinical_encounter_note_immutable_once_signed`.
 */
enum class EncounterNoteStatus {
    DRAFT,
    SIGNED,
}
