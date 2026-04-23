package com.medcore.clinical.patient.model

/**
 * FHIR `Patient.gender` (administrative sex) — the HL7 v2
 * `AdministrativeGender` value set (Phase 4A.1, ADR-005 /
 * US Core Patient v6.x).
 *
 * Stored as TEXT with a CHECK constraint in `clinical.patient`
 * (V14). The wire form matches FHIR exactly (lowercase) so
 * PatientFhirMapper (Phase 4A.4) is a zero-translation
 * pass-through on this field.
 *
 * **Not to be confused with:**
 * - `sex_assigned_at_birth` (US Core extension — biological sex
 *   at birth, separate column)
 * - `gender_identity_code` (US Core extension — the patient's
 *   self-identified gender, SNOMED-coded, separate column)
 *
 * Administrative sex is the workflow-facing field: what the
 * front desk enters on intake, what insurance expects, what
 * reporting uses. It is NOT a clinical or identity attribute;
 * it is an administrative classification.
 */
enum class AdministrativeSex(val wireValue: String) {
    MALE("male"),
    FEMALE("female"),
    OTHER("other"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String): AdministrativeSex =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unknown AdministrativeSex wire value: $value")
    }
}
