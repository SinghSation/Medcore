package com.medcore.clinical.patient.write

import com.medcore.clinical.patient.model.PatientIdentifierType
import java.time.Instant
import java.util.UUID

/**
 * Command for `POST /api/v1/tenants/{slug}/patients/{patientId}/identifiers`
 * (Phase 4A.3).
 *
 * First pattern-validation reuse of `clinical-write-pattern.md` v1.0:
 * `[REQUIRED §2.1]` immutable data class; `[REQUIRED §2.2]` PHI
 * discipline — never logged; `[REQUIRED §2.3]` the command does NOT
 * use `Patchable<T>` because 4A.3 ships POST + DELETE only.
 *
 * ### PHI discipline
 *
 * `value` is PHI for DRIVERS_LICENSE and INSURANCE_MEMBER types
 * (identifier-style data element per 45 CFR §164.514(b)). Do NOT
 * emit this type to log lines, MDC keys, tracing attributes, or
 * error response bodies. Enforced by
 * `PatientIdentifierLogPhiLeakageTest` at CI time.
 *
 * ### Carry-forward visibility
 *
 * The `UNIQUE (patient_id, type, issuer, value)` constraint from
 * V14 means a REVOKED identifier (with non-null `valid_to`) still
 * counts toward uniqueness. A caller cannot re-add an identifier
 * with the same `(type, issuer, value)` tuple after revoking it.
 * Tracked as a 4A.3 carry-forward — amend to a partial unique
 * index `WHERE valid_to IS NULL` if a pilot clinic's workflow
 * demands re-add-after-revoke.
 *
 * ### Design decisions (per 4A.3 master plan approval)
 *
 * - Authority reuse: [com.medcore.platform.security.MedcoreAuthority.PATIENT_UPDATE]
 *   rather than a new `PATIENT_IDENTIFIER_CREATE` entry. See
 *   `clinical-write-pattern.md` §10 checklist for the "does this
 *   feature need a new authority?" judgement.
 * - SSN not accepted (not in [PatientIdentifierType]). Compliance
 *   surface for SSN remains a future dedicated slice.
 */
data class AddPatientIdentifierCommand(
    val slug: String,
    val patientId: UUID,

    val type: PatientIdentifierType,
    val issuer: String,
    val value: String,

    val validFrom: Instant? = null,
    val validTo: Instant? = null,
)
