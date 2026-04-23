package com.medcore.clinical.encounter.model

/**
 * FHIR Encounter.class narrowed to Medcore's Phase 4C.1 scope.
 *
 * Only ambulatory (`AMB`) is supported in the initial VS1 shell
 * because DPC — the first pilot niche — is ambulatory-only.
 * Expansion to EMER / IMP / HH / VR is a Phase 4C carry-forward
 * requiring:
 *   - the V18 CHECK constraint updated
 *   - a clinical-context review per new class
 *   - a pilot-clinic requirement justifying the scope growth
 */
enum class EncounterClass {
    /** Ambulatory — outpatient visit, the DPC default. */
    AMB,
}
