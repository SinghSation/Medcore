package com.medcore.clinical.allergy.model

/**
 * FHIR-aligned allergy severity / criticality (Phase 4E.1).
 *
 * Closed enum — matches the DB CHECK constraint
 * `ck_clinical_allergy_severity` added in V24. Adding a new
 * severity requires a superseding ADR + migration.
 *
 * The four tokens map to the FHIR R4 `AllergyIntolerance.criticality`
 * value set ('low' / 'high' / 'unable-to-assess') extended with a
 * mid-level and a top-level for clinically actionable decisions.
 * Phase 5A FHIR-mapping will project these onto the FHIR value set
 * (likely MILD → 'low', MODERATE/SEVERE → 'high',
 * LIFE_THREATENING → 'high' with severity flag).
 *
 * No clinical safety logic keys on these tokens in 4E.1 — the UI
 * uses them for display and the audit row records them as a
 * closed-enum reason token. Drug-allergy interaction checking
 * is a Phase 7+ CDS slice.
 */
enum class AllergySeverity {
    MILD,
    MODERATE,
    SEVERE,
    LIFE_THREATENING,
}
