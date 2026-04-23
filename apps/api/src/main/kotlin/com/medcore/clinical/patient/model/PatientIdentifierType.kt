package com.medcore.clinical.patient.model

/**
 * Taxonomy of external identifiers that may attach to a patient
 * (Phase 4A.1).
 *
 * - [MRN_EXTERNAL] — an MRN from a DIFFERENT clinical system
 *   (e.g., the patient's MRN at a previous clinic, useful for
 *   lab / referral / record-linking workflows). NOT the Medcore
 *   MRN — that lives on `patient.mrn` directly.
 * - [DRIVERS_LICENSE] — state-issued driver's license number.
 *   `issuer` carries the issuing state (`CA`, `NY`, etc.).
 * - [INSURANCE_MEMBER] — insurance member ID. `issuer` carries
 *   the insurance company name or payer ID.
 * - [OTHER] — catch-all for one-off identifiers that don't
 *   warrant their own enum entry. `issuer` + `value` must be
 *   self-describing.
 *
 * **SSN deliberately absent** (Phase 4A.1 scope decision per
 * design-pack §9.3). SSN storage introduces real compliance
 * surface (HIPAA minimum-necessary, state laws like CCPA / NY
 * SHIELD); adding it is an additive enum update in a future
 * slice with its own PHI-exposure review.
 *
 * **`MRN_EXTERNAL` is NOT the Medcore MRN.** The Medcore MRN is
 * the authoritative display identifier on `patient.mrn`, stored
 * directly on the row with the `(tenant_id, mrn)` uniqueness
 * constraint. External MRNs are informational references to
 * other systems.
 */
enum class PatientIdentifierType {
    MRN_EXTERNAL,
    DRIVERS_LICENSE,
    INSURANCE_MEMBER,
    OTHER,
}
