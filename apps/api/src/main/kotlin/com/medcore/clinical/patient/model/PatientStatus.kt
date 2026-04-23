package com.medcore.clinical.patient.model

/**
 * Lifecycle status of a `clinical.patient` row (Phase 4A.1).
 *
 * - [ACTIVE] — the patient record is live and visible via the
 *   standard RLS SELECT policy.
 * - [MERGED_AWAY] — the patient was merged INTO another patient
 *   record via the future merge workflow. The row is preserved
 *   (audit integrity), `merged_into_id` points to the surviving
 *   patient, and the record remains visible for merge-unwind
 *   workflows (24-hour reversibility window per the 4A design
 *   pack).
 * - [DELETED] — the patient was soft-deleted. RLS filters these
 *   rows OUT of every SELECT — they are invisible to the
 *   application even for admins. Forensic access (if ever
 *   required) goes through a future SECURITY DEFINER helper,
 *   never the RLS read path.
 *
 * The `ck_clinical_patient_merged_fields_coherent` CHECK
 * constraint in V14 enforces that merge-related columns are
 * coherent with this status value — cannot have `merged_into_id`
 * on a non-MERGED_AWAY row, cannot lack it on a MERGED_AWAY row.
 */
enum class PatientStatus {
    ACTIVE,
    MERGED_AWAY,
    DELETED,
}
