package com.medcore.clinical.patient.fhir

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.time.LocalDate

/**
 * Minimal FHIR R4 `Patient` resource DTO (Phase 4A.5).
 *
 * **Wording discipline:** This is a **minimal FHIR R4 Patient
 * mapping** influenced by US Core Patient — NOT a claim of
 * US Core v6.x profile conformance. Profile conformance
 * requires race / ethnicity / telecom / address / multiple
 * identifiers and the associated metadata, none of which are
 * in 4A.5 scope. Calling this "US Core Patient v6.x" would
 * misrepresent what the code actually does.
 *
 * ### Wire shape
 *
 * Jackson serialises this class to FHIR R4 Patient JSON
 * directly. NO wrapping in [com.medcore.platform.api.ApiResponse] —
 * FHIR owns the response wire shape per the FHIR R4 spec.
 * Request correlation travels in the `X-Request-Id` HTTP
 * header (see `PatientController.getPatientAsFhir`).
 *
 * `@JsonInclude(NON_NULL)` + `NON_EMPTY` omit unpopulated
 * optional fields; FHIR consumers expect absent fields rather
 * than `null` or `[]`.
 *
 * ### What 4A.5 maps
 *
 * - `resourceType` (literal "Patient")
 * - `id` (patient UUID)
 * - `meta.versionId` (row_version) + `meta.lastUpdated` (updated_at)
 * - `identifier[0]` — the Medcore MRN only
 * - `active` — derived from `status == ACTIVE`
 * - `name[0]` — `use: official` with family, given, prefix, suffix
 * - `name[1]` — `use: usual` built from `preferredName` (omitted when null)
 * - `birthDate`
 * - `gender` (FHIR wire value already stored natively)
 * - `extension` — US Core birth-sex + gender-identity when non-null
 * - `communication[0].language` when `preferredLanguage` is non-null
 *
 * ### What 4A.5 does NOT map (deliberate scope)
 *
 * - Satellite `patient_identifier` rows (DRIVERS_LICENSE,
 *   INSURANCE_MEMBER, MRN_EXTERNAL, OTHER). Adding those is a
 *   later additive slice with its own PHI-exposure review.
 * - `telecom` — phone / email not stored on patient yet
 * - `address` — not stored; 4A.3.1 territory
 * - `contact` — next-of-kin not stored
 * - Race / ethnicity US Core extensions — 4A.1 design deferred
 * - Marital status, communication language preference, etc.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class FhirPatient(
    val resourceType: String = "Patient",
    val id: String,
    val meta: FhirMeta,
    val identifier: List<FhirIdentifier>,
    val active: Boolean,
    val name: List<FhirHumanName>,
    val gender: String,
    val birthDate: LocalDate,
    val extension: List<FhirExtension> = emptyList(),
    val communication: List<FhirCommunication> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FhirMeta(
    val versionId: String,
    val lastUpdated: Instant,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FhirIdentifier(
    /** FHIR code: `usual` | `official` | `temp` | `secondary` | `old` */
    val use: String,
    val system: String,
    val value: String,
    val type: FhirCodeableConcept? = null,
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class FhirHumanName(
    /** FHIR code: `usual` | `official` | `temp` | `nickname` | `anonymous` | `old` | `maiden` */
    val use: String,
    val family: String? = null,
    val given: List<String> = emptyList(),
    val prefix: List<String> = emptyList(),
    val suffix: List<String> = emptyList(),
    val text: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class FhirCodeableConcept(
    val coding: List<FhirCoding> = emptyList(),
    val text: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FhirCoding(
    val system: String? = null,
    val code: String? = null,
    val display: String? = null,
)

/**
 * FHIR R4 extension. Exactly ONE of [valueCode], [valueCoding],
 * [valueString] should be populated; others remain null.
 *
 * US Core uses `valueCode` for birth-sex
 * (`us-core-birthsex`: M/F/UNK) and `valueCoding` for
 * gender-identity (`us-core-genderIdentity`, SNOMED-coded).
 * 4A.5 maps gender-identity as `valueString` because the
 * source column `gender_identity_code` is a free-string input
 * today — if a future slice adds SNOMED-coded input, the
 * mapper can be upgraded to `valueCoding` additively.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FhirExtension(
    val url: String,
    @JsonProperty("valueCode") val valueCode: String? = null,
    @JsonProperty("valueCoding") val valueCoding: FhirCoding? = null,
    @JsonProperty("valueString") val valueString: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FhirCommunication(
    val language: FhirCodeableConcept,
    val preferred: Boolean? = null,
)
