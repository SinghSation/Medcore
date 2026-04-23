package com.medcore.clinical.patient.write

import com.medcore.clinical.patient.model.AdministrativeSex
import java.time.LocalDate

/**
 * Command for `POST /api/v1/tenants/{slug}/patients` (Phase 4A.2) —
 * creates a new `clinical.patient` row via the WriteGate perimeter.
 *
 * ### Design notes
 *
 * - **`slug`** carries the target tenant's slug (not UUID). The
 *   tenancy resolver + AuthorityResolver work in terms of slugs
 *   consistently with 3J.2 / 3J.3 / 3J.N.
 * - **`administrativeSex`** is the closed-enum Kotlin value. The
 *   HTTP DTO accepts FHIR wire values (lowercase); the controller
 *   coerces via [AdministrativeSex.fromWire] before constructing
 *   the command. Keeps the domain layer free of wire-format
 *   concerns.
 * - **`birthDate`** is a `java.time.LocalDate` — no timezone
 *   ambiguity.
 * - **`confirmDuplicate`** is the `X-Confirm-Duplicate: true`
 *   header bypass. When `false` (default), duplicate detection
 *   runs; when `true`, the handler skips the detection step and
 *   proceeds to create. Deliberately a boolean flag rather than a
 *   free-form "override reason" string — we don't want
 *   user-supplied text on the create path to leak into logs.
 * - **No patient_identifier in 4A.2.** The satellite is a distinct
 *   surface, scheduled for a later slice. Identifiers never appear
 *   on the create command.
 * - **MRN is NOT on the command.** It's minted server-side by
 *   `MrnGenerator` inside the handler. Letting the caller supply
 *   an MRN is the IMPORTED path — reserved for a future slice
 *   with its own compliance review.
 *
 * ### Fields carry PHI
 *
 * Name parts, DOB, administrative sex, sex assigned at birth, and
 * preferred language / gender identity are all PHI per
 * 45 CFR §164.514(b). Logging discipline: this command type MUST
 * NOT be logged by `toString` — handlers treat it opaquely.
 * `PatientLogPhiLeakageTest` enforces.
 */
data class CreatePatientCommand(
    val slug: String,

    // HumanName (FHIR Patient.name[use='official'])
    val nameGiven: String,
    val nameFamily: String,
    val nameMiddle: String? = null,
    val nameSuffix: String? = null,
    val namePrefix: String? = null,

    // HumanName (FHIR Patient.name[use='usual'])
    val preferredName: String? = null,

    // FHIR Patient.birthDate
    val birthDate: LocalDate,

    // FHIR Patient.gender
    val administrativeSex: AdministrativeSex,

    // US Core extensions
    val sexAssignedAtBirth: String? = null,
    val genderIdentityCode: String? = null,

    // FHIR Patient.communication.language
    val preferredLanguage: String? = null,

    /**
     * Duplicate-warning bypass. `true` means the caller has
     * acknowledged an earlier 409 warning response and is
     * intentionally creating a candidate-matching patient anyway.
     */
    val confirmDuplicate: Boolean = false,
)
