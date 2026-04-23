package com.medcore.clinical.encounter.model

/**
 * Lifecycle state of a `clinical.encounter` row (Phase 4C.1).
 *
 * Closed enum. Wire form is the enum NAME (uppercase) — the
 * V18 CHECK constraint accepts these literal strings.
 *
 * ### States
 *
 * - [PLANNED] — scheduled but not yet in progress. Not written
 *   by any VS1 Chunk D path (scheduling is Phase 4B). Reserved
 *   for the future scheduling slice.
 * - [IN_PROGRESS] — encounter is happening now. Written by
 *   `POST /encounters` (VS1 Chunk D).
 * - [FINISHED] — encounter complete. Transition from IN_PROGRESS
 *   is a later Phase 4C slice.
 * - [CANCELLED] — encounter aborted at any lifecycle point.
 *   Transition is a later Phase 4C slice.
 *
 * The V18 `ck_clinical_encounter_lifecycle_coherence` constraint
 * enforces the timestamp invariants per status.
 */
enum class EncounterStatus {
    PLANNED,
    IN_PROGRESS,
    FINISHED,
    CANCELLED,
}
