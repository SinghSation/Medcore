package com.medcore.clinical.allergy.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.medcore.clinical.allergy.model.AllergySeverity
import com.medcore.clinical.allergy.model.AllergyStatus
import com.medcore.clinical.allergy.read.ListPatientAllergiesResult
import com.medcore.clinical.allergy.write.AddAllergyCommand
import com.medcore.clinical.allergy.write.AllergySnapshot
import com.medcore.clinical.allergy.write.UpdateAllergyCommand
import com.medcore.clinical.patient.write.Patchable
import com.medcore.platform.write.WriteValidationException
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ============================================================================
// POST /api/v1/tenants/{slug}/patients/{patientId}/allergies — request body
// ============================================================================

/**
 * HTTP DTO for the add-allergy POST (Phase 4E.1).
 *
 * Wire shape:
 * ```
 * {
 *   "substanceText": "Penicillin",
 *   "severity": "SEVERE",
 *   "reactionText": "Anaphylaxis, hospitalised in 2018",   // optional
 *   "onsetDate": "2018-04-12",                              // optional
 *   "recordedInEncounterId": "<uuid>"                       // optional
 * }
 * ```
 *
 * `severity` is parsed against [AllergySeverity] — unknown
 * tokens throw [WriteValidationException] → 422
 * `request.validation_failed|format`. Same pattern as
 * `EncounterClass` / `CancelReason` parsing in 4C.1 / 4C.5.
 */
data class AddAllergyRequest(
    @field:NotNull val substanceText: String?,
    @field:NotNull val severity: String?,
    val reactionText: String? = null,
    val onsetDate: LocalDate? = null,
    val recordedInEncounterId: UUID? = null,
) {
    fun toCommand(slug: String, patientId: UUID): AddAllergyCommand {
        val rawSubstance = substanceText
            ?: throw WriteValidationException(field = "substanceText", code = "required")
        val rawSeverity = severity
            ?: throw WriteValidationException(field = "severity", code = "required")
        val parsedSeverity = parseSeverity(rawSeverity)
        return AddAllergyCommand(
            slug = slug,
            patientId = patientId,
            substanceText = rawSubstance,
            severity = parsedSeverity,
            reactionText = reactionText,
            onsetDate = onsetDate,
            recordedInEncounterId = recordedInEncounterId,
        )
    }
}

// ============================================================================
// PATCH /api/v1/tenants/{slug}/patients/{patientId}/allergies/{id} — body
// ============================================================================

/**
 * Builds an [UpdateAllergyCommand] from a JSON body (Phase 4E.1).
 *
 * Mirrors `UpdatePatientDemographicsRequestMapper`'s three-state
 * walk: absent / null / value → [Patchable.Absent] /
 * [Patchable.Clear] / [Patchable.Set]. The validator enforces
 * legality (e.g., `Clear` is illegal on `severity` / `status`
 * which are non-null DB columns).
 *
 * Allowed wire fields:
 *   - `severity` — `AllergySeverity` enum token (string)
 *   - `reactionText` — string (Clear allowed)
 *   - `onsetDate` — ISO date (Clear allowed)
 *   - `status` — `AllergyStatus` enum token (string). Status
 *     transitions to ENTERED_IN_ERROR are the retraction path
 *     (handler dispatches to the REVOKED audit action).
 *
 * Unknown fields are silently ignored — callers cannot smuggle
 * updates to `substanceText`, `tenantId`, or any audit column
 * via the PATCH body.
 */
internal object UpdateAllergyRequestMapper {

    fun toCommand(
        slug: String,
        patientId: UUID,
        allergyId: UUID,
        expectedRowVersion: Long,
        body: JsonNode?,
    ): UpdateAllergyCommand {
        if (body == null || !body.isObject) {
            throw WriteValidationException(field = "body", code = "malformed")
        }
        return UpdateAllergyCommand(
            slug = slug,
            patientId = patientId,
            allergyId = allergyId,
            expectedRowVersion = expectedRowVersion,
            severity = severityPatch(body, "severity"),
            reactionText = stringPatch(body, "reactionText"),
            onsetDate = datePatch(body, "onsetDate"),
            status = statusPatch(body, "status"),
        )
    }

    private fun stringPatch(body: JsonNode, field: String): Patchable<String> {
        if (!body.has(field)) return Patchable.Absent
        val node = body.get(field)
        if (node is NullNode || node.isNull) return Patchable.Clear
        if (!node.isTextual) {
            throw WriteValidationException(field = field, code = "not_string")
        }
        return Patchable.Set(node.asText())
    }

    private fun datePatch(body: JsonNode, field: String): Patchable<LocalDate> {
        if (!body.has(field)) return Patchable.Absent
        val node = body.get(field)
        if (node is NullNode || node.isNull) return Patchable.Clear
        if (!node.isTextual) {
            throw WriteValidationException(field = field, code = "not_date")
        }
        return try {
            Patchable.Set(LocalDate.parse(node.asText()))
        } catch (ex: Exception) {
            throw WriteValidationException(field = field, code = "not_date", cause = ex)
        }
    }

    private fun severityPatch(body: JsonNode, field: String): Patchable<AllergySeverity> {
        if (!body.has(field)) return Patchable.Absent
        val node = body.get(field)
        if (node is NullNode || node.isNull) return Patchable.Clear
        if (!node.isTextual) {
            throw WriteValidationException(field = field, code = "not_string")
        }
        return Patchable.Set(parseSeverity(node.asText(), fieldName = field))
    }

    private fun statusPatch(body: JsonNode, field: String): Patchable<AllergyStatus> {
        if (!body.has(field)) return Patchable.Absent
        val node = body.get(field)
        if (node is NullNode || node.isNull) return Patchable.Clear
        if (!node.isTextual) {
            throw WriteValidationException(field = field, code = "not_string")
        }
        return Patchable.Set(parseStatus(node.asText(), fieldName = field))
    }
}

private fun parseSeverity(
    raw: String,
    fieldName: String = "severity",
): AllergySeverity = try {
    AllergySeverity.valueOf(raw)
} catch (ex: IllegalArgumentException) {
    throw WriteValidationException(field = fieldName, code = "format", cause = ex)
}

private fun parseStatus(
    raw: String,
    fieldName: String = "status",
): AllergyStatus = try {
    AllergyStatus.valueOf(raw)
} catch (ex: IllegalArgumentException) {
    throw WriteValidationException(field = fieldName, code = "format", cause = ex)
}

// ============================================================================
// Response DTOs
// ============================================================================

/**
 * Wire shape for allergy create / update / list responses
 * (Phase 4E.1).
 *
 * `@JsonInclude(NON_NULL)` — optional clinical fields
 * (`reactionText`, `onsetDate`, `recordedInEncounterId`,
 * `substanceCode`, `substanceSystem`) are omitted from the wire
 * when unset, matching FHIR cardinality semantics. The frontend
 * relies on absence-as-omission for the banner-vs-management
 * display.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AllergyResponse(
    val id: UUID,
    val tenantId: UUID,
    val patientId: UUID,
    val substanceText: String,
    val substanceCode: String?,
    val substanceSystem: String?,
    val severity: AllergySeverity,
    val status: AllergyStatus,
    val reactionText: String?,
    val onsetDate: LocalDate?,
    val recordedInEncounterId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID,
    val updatedBy: UUID,
    val rowVersion: Long,
) {
    companion object {
        fun from(snapshot: AllergySnapshot): AllergyResponse =
            AllergyResponse(
                id = snapshot.id,
                tenantId = snapshot.tenantId,
                patientId = snapshot.patientId,
                substanceText = snapshot.substanceText,
                substanceCode = snapshot.substanceCode,
                substanceSystem = snapshot.substanceSystem,
                severity = snapshot.severity,
                status = snapshot.status,
                reactionText = snapshot.reactionText,
                onsetDate = snapshot.onsetDate,
                recordedInEncounterId = snapshot.recordedInEncounterId,
                createdAt = snapshot.createdAt,
                updatedAt = snapshot.updatedAt,
                createdBy = snapshot.createdBy,
                updatedBy = snapshot.updatedBy,
                rowVersion = snapshot.rowVersion,
            )
    }
}

/**
 * Wire envelope for the list endpoint (Phase 4E.1, paginated
 * as of platform-pagination chunk D, ADR-009).
 *
 * Carries `pageInfo` per ADR-009 §2.4 — same shape as the
 * other paginated clinical list responses (encounter-notes,
 * encounters, problems).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AllergyListResponse(
    val items: List<AllergyResponse>,
    val pageInfo: com.medcore.platform.api.PageInfoDto,
) {
    companion object {
        fun from(result: ListPatientAllergiesResult): AllergyListResponse =
            AllergyListResponse(
                items = result.items.map { AllergyResponse.from(it) },
                pageInfo = com.medcore.platform.api.PageInfoDto.from(result.pageInfo),
            )
    }
}
