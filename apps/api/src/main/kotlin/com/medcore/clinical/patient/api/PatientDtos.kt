package com.medcore.clinical.patient.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.medcore.clinical.patient.model.AdministrativeSex
import com.medcore.clinical.patient.model.MrnSource
import com.medcore.clinical.patient.model.PatientIdentifierType
import com.medcore.clinical.patient.model.PatientStatus
import com.medcore.clinical.patient.write.AddPatientIdentifierCommand
import com.medcore.clinical.patient.write.CreatePatientCommand
import com.medcore.clinical.patient.write.Patchable
import com.medcore.clinical.patient.write.PatientIdentifierSnapshot
import com.medcore.clinical.patient.write.PatientSnapshot
import com.medcore.clinical.patient.write.UpdatePatientDemographicsCommand
import com.medcore.platform.write.WriteValidationException
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ============================================================================
// POST /api/v1/tenants/{slug}/patients — create request body
// ============================================================================

/**
 * HTTP DTO for `POST /api/v1/tenants/{slug}/patients` (Phase 4A.2).
 *
 * Bean-validation runs at the MVC boundary (`@Valid`). The
 * [CreatePatientValidator] runs AFTER DTO binding and picks up
 * the domain checks annotations cannot express (trim-emptiness,
 * control chars, date bounds).
 *
 * Administrative-sex is sent as the FHIR wire string (`male` /
 * `female` / `other` / `unknown`) and coerced to the Kotlin
 * closed-enum in [toCommand]. Wire format is the canonical form
 * the UI and FHIR exchanges will use — keeps the HTTP layer
 * FHIR-native.
 */
data class CreatePatientRequest(
    @field:NotBlank
    val nameGiven: String?,

    @field:NotBlank
    val nameFamily: String?,

    val nameMiddle: String? = null,
    val nameSuffix: String? = null,
    val namePrefix: String? = null,
    val preferredName: String? = null,

    val birthDate: LocalDate?,

    // Always sent as the FHIR wire value (lowercase). `null` is
    // rejected at the validator layer via WriteValidationException
    // (Bean Validation cannot validate enum-value strings).
    val administrativeSex: String?,

    val sexAssignedAtBirth: String? = null,
    val genderIdentityCode: String? = null,
    val preferredLanguage: String? = null,
) {
    /**
     * Materialises a [CreatePatientCommand]. Applied AFTER `@Valid`
     * enforces `@NotBlank` / non-null on the required fields.
     *
     * Throws [WriteValidationException] on
     * `administrativeSex` if the wire value is not a legal
     * [AdministrativeSex]. Matches the 422 envelope that Bean
     * Validation uses for other constraint violations.
     */
    fun toCommand(slug: String, confirmDuplicate: Boolean): CreatePatientCommand {
        val rawSex = administrativeSex
            ?: throw WriteValidationException(
                field = "administrativeSex",
                code = "required",
            )
        val sex = try {
            AdministrativeSex.fromWire(rawSex)
        } catch (ex: IllegalStateException) {
            throw WriteValidationException(
                field = "administrativeSex",
                code = "format",
                cause = ex,
            )
        }
        return CreatePatientCommand(
            slug = slug,
            nameGiven = nameGiven!!,
            nameFamily = nameFamily!!,
            nameMiddle = nameMiddle,
            nameSuffix = nameSuffix,
            namePrefix = namePrefix,
            preferredName = preferredName,
            birthDate = birthDate
                ?: throw WriteValidationException(field = "birthDate", code = "required"),
            administrativeSex = sex,
            sexAssignedAtBirth = sexAssignedAtBirth,
            genderIdentityCode = genderIdentityCode,
            preferredLanguage = preferredLanguage,
            confirmDuplicate = confirmDuplicate,
        )
    }
}

// ============================================================================
// PATCH /api/v1/tenants/{slug}/patients/{patientId} — update request body
// ============================================================================

/**
 * Converts a JSON body into an [UpdatePatientDemographicsCommand]
 * using the three-state [Patchable] semantics (Phase 4A.2).
 *
 * The `PatientController` binds the request body as a [JsonNode]
 * (NOT a typed DTO) because Jackson cannot natively represent the
 * absent/null/value distinction. For each known patchable field:
 *
 *   - node absent from JSON  → [Patchable.Absent]
 *   - node present and null  → [Patchable.Clear]
 *   - node present with value → [Patchable.Set]
 *
 * Fields not in the allowed set are silently ignored — callers
 * cannot smuggle updates to, e.g., `mrn` or `status` through the
 * PATCH body. The validator enforces that at least one field was
 * sent (empty-body PATCH → 422 `no_fields`).
 *
 * Administrative-sex wire-value parsing throws
 * [WriteValidationException] → 422, same as the create path.
 */
internal object UpdatePatientDemographicsRequestMapper {

    fun toCommand(
        slug: String,
        patientId: UUID,
        expectedRowVersion: Long,
        body: JsonNode?,
    ): UpdatePatientDemographicsCommand {
        // Null / non-object body is a caller error, not a no-op.
        if (body == null || !body.isObject) {
            throw WriteValidationException(field = "body", code = "malformed")
        }
        return UpdatePatientDemographicsCommand(
            slug = slug,
            patientId = patientId,
            expectedRowVersion = expectedRowVersion,
            nameGiven = stringPatch(body, "nameGiven"),
            nameFamily = stringPatch(body, "nameFamily"),
            nameMiddle = stringPatch(body, "nameMiddle"),
            nameSuffix = stringPatch(body, "nameSuffix"),
            namePrefix = stringPatch(body, "namePrefix"),
            preferredName = stringPatch(body, "preferredName"),
            birthDate = datePatch(body, "birthDate"),
            administrativeSex = adminSexPatch(body, "administrativeSex"),
            sexAssignedAtBirth = stringPatch(body, "sexAssignedAtBirth"),
            genderIdentityCode = stringPatch(body, "genderIdentityCode"),
            preferredLanguage = stringPatch(body, "preferredLanguage"),
        )
    }

    private fun stringPatch(body: JsonNode, field: String): Patchable<String> {
        if (!body.has(field)) return Patchable.Absent
        val node = body.get(field)
        if (node is NullNode || node.isNull) return Patchable.Clear
        if (!node.isTextual) throw WriteValidationException(field = field, code = "not_string")
        return Patchable.Set(node.asText())
    }

    private fun datePatch(body: JsonNode, field: String): Patchable<LocalDate> {
        if (!body.has(field)) return Patchable.Absent
        val node = body.get(field)
        if (node is NullNode || node.isNull) return Patchable.Clear
        if (!node.isTextual) throw WriteValidationException(field = field, code = "not_date")
        return try {
            Patchable.Set(LocalDate.parse(node.asText()))
        } catch (ex: Exception) {
            throw WriteValidationException(field = field, code = "not_date", cause = ex)
        }
    }

    private fun adminSexPatch(body: JsonNode, field: String): Patchable<AdministrativeSex> {
        if (!body.has(field)) return Patchable.Absent
        val node = body.get(field)
        if (node is NullNode || node.isNull) return Patchable.Clear
        if (!node.isTextual) throw WriteValidationException(field = field, code = "not_string")
        return try {
            Patchable.Set(AdministrativeSex.fromWire(node.asText()))
        } catch (ex: IllegalStateException) {
            throw WriteValidationException(field = field, code = "format", cause = ex)
        }
    }
}

// ============================================================================
// Response DTO
// ============================================================================

/**
 * Wire shape for patient create / update responses (Phase 4A.2).
 *
 * Mirrors [PatientSnapshot]. Wire format for `administrativeSex`
 * is the FHIR lowercase string (matches the create request DTO's
 * input format). `rowVersion` on the response lets clients send
 * the right `If-Match` on their next PATCH; the HTTP response
 * ALSO carries the value as an `ETag` header (RFC 7232) for
 * intermediaries.
 *
 * `@JsonInclude(NON_NULL)` — nullable demographic columns are
 * omitted from the wire when unset, matching FHIR's cardinality
 * semantics.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PatientResponse(
    val id: UUID,
    val tenantId: UUID,
    val mrn: String,
    val mrnSource: MrnSource,

    val nameGiven: String,
    val nameFamily: String,
    val nameMiddle: String?,
    val nameSuffix: String?,
    val namePrefix: String?,
    val preferredName: String?,

    val birthDate: LocalDate,
    val administrativeSex: String,
    val sexAssignedAtBirth: String?,
    val genderIdentityCode: String?,
    val preferredLanguage: String?,

    val status: PatientStatus,
    val rowVersion: Long,

    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID,
    val updatedBy: UUID,
) {
    companion object {
        fun from(snapshot: PatientSnapshot): PatientResponse = PatientResponse(
            id = snapshot.id,
            tenantId = snapshot.tenantId,
            mrn = snapshot.mrn,
            mrnSource = snapshot.mrnSource,
            nameGiven = snapshot.nameGiven,
            nameFamily = snapshot.nameFamily,
            nameMiddle = snapshot.nameMiddle,
            nameSuffix = snapshot.nameSuffix,
            namePrefix = snapshot.namePrefix,
            preferredName = snapshot.preferredName,
            birthDate = snapshot.birthDate,
            administrativeSex = snapshot.administrativeSex.wireValue,
            sexAssignedAtBirth = snapshot.sexAssignedAtBirth,
            genderIdentityCode = snapshot.genderIdentityCode,
            preferredLanguage = snapshot.preferredLanguage,
            status = snapshot.status,
            rowVersion = snapshot.rowVersion,
            createdAt = snapshot.createdAt,
            updatedAt = snapshot.updatedAt,
            createdBy = snapshot.createdBy,
            updatedBy = snapshot.updatedBy,
        )
    }
}

// ============================================================================
// Phase 4B.1 — patient list response (Vertical Slice 1, Chunk B)
// ============================================================================

/**
 * Summary-view wire shape for a single patient in the list
 * response (Phase 4B.1).
 *
 * **Deliberately narrower than [PatientResponse]**. The list
 * endpoint is a summary view for clinician navigation — a
 * "find the patient I want to open" surface — and carries the
 * minimum PHI needed for that task:
 *
 *   - [id] for detail-page navigation
 *   - [mrn] for identification
 *   - [nameGiven] + [nameFamily] for display
 *   - [birthDate] for disambiguation across same-name rows
 *   - [administrativeSex] for disambiguation
 *   - [createdAt] for ordering transparency
 *
 * **Fields intentionally NOT in the list**: middle/suffix/prefix
 * names, preferred name, preferred language, sex-assigned-at-
 * birth, gender identity code, status, row_version, identifiers
 * beyond MRN, created_by/updated_by, updatedAt, addresses, phone
 * numbers. The detail endpoint ([PatientResponse] via
 * `GET /patients/{id}`) is the full surface.
 *
 * Narrower wire = less PHI on the wire for the common case
 * (list fetch is called far more often than detail fetch), less
 * serialisation cost, and less client-side PHI retention.
 *
 * `administrativeSex` renders as the FHIR wire string (lowercase)
 * matching [PatientResponse].
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PatientListItemResponse(
    val id: UUID,
    val mrn: String,
    val nameGiven: String,
    val nameFamily: String,
    val birthDate: LocalDate,
    val administrativeSex: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(snapshot: PatientSnapshot): PatientListItemResponse =
            PatientListItemResponse(
                id = snapshot.id,
                mrn = snapshot.mrn,
                nameGiven = snapshot.nameGiven,
                nameFamily = snapshot.nameFamily,
                birthDate = snapshot.birthDate,
                administrativeSex = snapshot.administrativeSex.wireValue,
                createdAt = snapshot.createdAt,
            )
    }
}

/**
 * Wire envelope for `GET /api/v1/tenants/{slug}/patients`
 * (Phase 4B.1). Wraps the page of items with pagination
 * metadata so the frontend can render Prev/Next + totals
 * without a second round-trip.
 *
 * `hasMore` is derived (`offset + items.size < totalCount`)
 * and exposed on the wire for UI convenience — clients that
 * prefer to compute locally can ignore it.
 *
 * `totalCount` reflects rows visible under the caller's RLS
 * envelope, not the raw tenant population. For 4B.1's policies
 * the two match for any ACTIVE member of a single tenant.
 */
data class PatientListResponse(
    val items: List<PatientListItemResponse>,
    val totalCount: Long,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean,
) {
    companion object {
        fun from(
            result: com.medcore.clinical.patient.read.ListPatientsResult,
        ): PatientListResponse = PatientListResponse(
            items = result.items.map { PatientListItemResponse.from(it) },
            totalCount = result.totalCount,
            limit = result.limit,
            offset = result.offset,
            hasMore = result.hasMore,
        )
    }
}

// ============================================================================
// Phase 4A.3 — patient identifier satellite (add + revoke)
// ============================================================================

/**
 * HTTP DTO for
 * `POST /api/v1/tenants/{slug}/patients/{patientId}/identifiers`
 * (Phase 4A.3).
 *
 * Bean Validation handles `@NotNull` / `@NotBlank`;
 * [com.medcore.clinical.patient.write.AddPatientIdentifierValidator]
 * runs the domain checks (post-trim emptiness, control chars,
 * length caps, valid_range coherence).
 *
 * ### `type` coercion
 *
 * `type` is sent as the closed-enum wire name
 * (`MRN_EXTERNAL` / `DRIVERS_LICENSE` / `INSURANCE_MEMBER` /
 * `OTHER`). [toCommand] resolves via `enumValueOf<PatientIdentifierType>`;
 * an unknown value throws [WriteValidationException]
 * `field=type code=format` → 422.
 *
 * ### PHI
 *
 * `value` is PHI for DL / Insurance types. Never logged —
 * enforced by [com.medcore.clinical.patient.api.PatientIdentifierLogPhiLeakageTest].
 */
data class AddPatientIdentifierRequest(
    @field:NotBlank
    val type: String?,

    @field:NotBlank
    val issuer: String?,

    @field:NotBlank
    val value: String?,

    val validFrom: Instant? = null,
    val validTo: Instant? = null,
) {
    fun toCommand(slug: String, patientId: UUID): AddPatientIdentifierCommand {
        val rawType = type
            ?: throw WriteValidationException(field = "type", code = "required")
        val parsedType = try {
            enumValueOf<PatientIdentifierType>(rawType)
        } catch (ex: IllegalArgumentException) {
            throw WriteValidationException(field = "type", code = "format", cause = ex)
        }
        return AddPatientIdentifierCommand(
            slug = slug,
            patientId = patientId,
            type = parsedType,
            issuer = issuer!!,
            value = value!!,
            validFrom = validFrom,
            validTo = validTo,
        )
    }
}

/**
 * Response DTO for identifier endpoints (Phase 4A.3).
 *
 * Mirrors [PatientIdentifierSnapshot] with one wire-shaping
 * adjustment: [type] emits the closed-enum NAME (uppercase) —
 * the canonical wire form matching [PatientIdentifierType].
 * This is consistent with 3J.N's `role` field on membership
 * responses (closed-enum names, uppercase).
 *
 * `@JsonInclude(NON_NULL)` omits `validFrom` / `validTo` when
 * the identifier has no explicit validity window.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PatientIdentifierResponse(
    val id: UUID,
    val patientId: UUID,
    val tenantId: UUID,

    val type: PatientIdentifierType,
    val issuer: String,
    val value: String,

    val validFrom: Instant?,
    val validTo: Instant?,

    val createdAt: Instant,
    val updatedAt: Instant,
    val rowVersion: Long,
) {
    companion object {
        fun from(snapshot: PatientIdentifierSnapshot): PatientIdentifierResponse =
            PatientIdentifierResponse(
                id = snapshot.id,
                patientId = snapshot.patientId,
                tenantId = snapshot.tenantId,
                type = snapshot.type,
                issuer = snapshot.issuer,
                value = snapshot.value,
                validFrom = snapshot.validFrom,
                validTo = snapshot.validTo,
                createdAt = snapshot.createdAt,
                updatedAt = snapshot.updatedAt,
                rowVersion = snapshot.rowVersion,
            )
    }
}
