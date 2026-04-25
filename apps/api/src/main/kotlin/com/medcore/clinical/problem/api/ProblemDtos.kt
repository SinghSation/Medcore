package com.medcore.clinical.problem.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.medcore.clinical.patient.write.Patchable
import com.medcore.clinical.problem.model.ProblemSeverity
import com.medcore.clinical.problem.model.ProblemStatus
import com.medcore.clinical.problem.read.ListPatientProblemsResult
import com.medcore.clinical.problem.write.AddProblemCommand
import com.medcore.clinical.problem.write.ProblemSnapshot
import com.medcore.clinical.problem.write.UpdateProblemCommand
import com.medcore.platform.write.WriteValidationException
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ============================================================================
// POST /api/v1/tenants/{slug}/patients/{patientId}/problems — request body
// ============================================================================

/**
 * HTTP DTO for the add-problem POST (Phase 4E.2).
 *
 * Wire shape:
 * ```
 * {
 *   "conditionText": "Type 2 diabetes mellitus",
 *   "severity": "MODERATE",                       // optional (NULLABLE)
 *   "onsetDate": "2018-04-12",                    // optional
 *   "abatementDate": "2024-01-15",                // optional
 *   "recordedInEncounterId": "<uuid>"             // optional
 * }
 * ```
 *
 * `severity` is parsed against [ProblemSeverity] when present —
 * unknown tokens throw [WriteValidationException] → 422
 * `request.validation_failed|format`. `null` / absent severity
 * is legitimate (locked Q3: severity is nullable for problems,
 * unlike allergies where it's required).
 */
data class AddProblemRequest(
    @field:NotNull val conditionText: String?,
    val severity: String? = null,
    val onsetDate: LocalDate? = null,
    val abatementDate: LocalDate? = null,
    val recordedInEncounterId: UUID? = null,
) {
    fun toCommand(slug: String, patientId: UUID): AddProblemCommand {
        val rawCondition = conditionText
            ?: throw WriteValidationException(field = "conditionText", code = "required")
        val parsedSeverity = severity?.let { parseSeverity(it) }
        return AddProblemCommand(
            slug = slug,
            patientId = patientId,
            conditionText = rawCondition,
            severity = parsedSeverity,
            onsetDate = onsetDate,
            abatementDate = abatementDate,
            recordedInEncounterId = recordedInEncounterId,
        )
    }
}

// ============================================================================
// PATCH /api/v1/tenants/{slug}/patients/{patientId}/problems/{id} — body
// ============================================================================

/**
 * Builds an [UpdateProblemCommand] from a JSON body (Phase 4E.2).
 *
 * Mirrors `UpdateAllergyRequestMapper`'s three-state walk:
 * absent / null / value → [Patchable.Absent] / [Patchable.Clear]
 * / [Patchable.Set]. The validator enforces legality (e.g.,
 * `Clear` is illegal on `status` which is non-null DB column,
 * but legal on `severity` which is nullable per locked Q3).
 *
 * Allowed wire fields:
 *   - `severity` — `ProblemSeverity` enum token (string).
 *     Clear is allowed (sets the column to NULL).
 *   - `onsetDate` — ISO date (Clear allowed).
 *   - `abatementDate` — ISO date (Clear allowed).
 *   - `status` — `ProblemStatus` enum token (string). Status
 *     transitions:
 *       * target ∈ {ACTIVE, INACTIVE} routes to UPDATED.
 *       * target = RESOLVED routes to RESOLVED action.
 *       * target = ENTERED_IN_ERROR routes to REVOKED action.
 *       * RESOLVED → INACTIVE refused at handler with
 *         `problem_invalid_transition`.
 *       * ENTERED_IN_ERROR → anything refused with
 *         `problem_terminal`.
 *
 * `conditionText` is intentionally NOT in the mapper — sending
 * it has no effect (silently ignored), which matches the
 * "allowed wire fields" contract above and prevents accidental
 * smuggling of an immutable-field update via PATCH.
 *
 * Unknown fields are silently ignored — callers cannot smuggle
 * updates to `conditionText`, `tenantId`, or any audit column
 * via the PATCH body.
 */
internal object UpdateProblemRequestMapper {

    fun toCommand(
        slug: String,
        patientId: UUID,
        problemId: UUID,
        expectedRowVersion: Long,
        body: JsonNode?,
    ): UpdateProblemCommand {
        if (body == null || !body.isObject) {
            throw WriteValidationException(field = "body", code = "malformed")
        }
        return UpdateProblemCommand(
            slug = slug,
            patientId = patientId,
            problemId = problemId,
            expectedRowVersion = expectedRowVersion,
            severity = severityPatch(body, "severity"),
            onsetDate = datePatch(body, "onsetDate"),
            abatementDate = datePatch(body, "abatementDate"),
            status = statusPatch(body, "status"),
        )
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

    private fun severityPatch(body: JsonNode, field: String): Patchable<ProblemSeverity> {
        if (!body.has(field)) return Patchable.Absent
        val node = body.get(field)
        if (node is NullNode || node.isNull) return Patchable.Clear
        if (!node.isTextual) {
            throw WriteValidationException(field = field, code = "not_string")
        }
        return Patchable.Set(parseSeverity(node.asText(), fieldName = field))
    }

    private fun statusPatch(body: JsonNode, field: String): Patchable<ProblemStatus> {
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
): ProblemSeverity = try {
    ProblemSeverity.valueOf(raw)
} catch (ex: IllegalArgumentException) {
    throw WriteValidationException(field = fieldName, code = "format", cause = ex)
}

private fun parseStatus(
    raw: String,
    fieldName: String = "status",
): ProblemStatus = try {
    ProblemStatus.valueOf(raw)
} catch (ex: IllegalArgumentException) {
    throw WriteValidationException(field = fieldName, code = "format", cause = ex)
}

// ============================================================================
// Response DTOs
// ============================================================================

/**
 * Wire shape for problem create / update / list responses
 * (Phase 4E.2).
 *
 * `@JsonInclude(NON_NULL)` — optional clinical fields
 * (`severity`, `onsetDate`, `abatementDate`,
 * `recordedInEncounterId`, `codeValue`, `codeSystem`) are
 * omitted from the wire when unset. Severity in particular
 * is genuinely optional (locked Q3) so absence-as-omission
 * is the FHIR-faithful encoding.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProblemResponse(
    val id: UUID,
    val tenantId: UUID,
    val patientId: UUID,
    val conditionText: String,
    val codeValue: String?,
    val codeSystem: String?,
    val severity: ProblemSeverity?,
    val status: ProblemStatus,
    val onsetDate: LocalDate?,
    val abatementDate: LocalDate?,
    val recordedInEncounterId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID,
    val updatedBy: UUID,
    val rowVersion: Long,
) {
    companion object {
        fun from(snapshot: ProblemSnapshot): ProblemResponse =
            ProblemResponse(
                id = snapshot.id,
                tenantId = snapshot.tenantId,
                patientId = snapshot.patientId,
                conditionText = snapshot.conditionText,
                codeValue = snapshot.codeValue,
                codeSystem = snapshot.codeSystem,
                severity = snapshot.severity,
                status = snapshot.status,
                onsetDate = snapshot.onsetDate,
                abatementDate = snapshot.abatementDate,
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
 * Wire envelope for the list endpoint (Phase 4E.2).
 *
 * Un-paginated, mirrors `AllergyListResponse`. Adding
 * pagination is additive in a later slice.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProblemListResponse(
    val items: List<ProblemResponse>,
) {
    companion object {
        fun from(result: ListPatientProblemsResult): ProblemListResponse =
            ProblemListResponse(
                items = result.items.map { ProblemResponse.from(it) },
            )
    }
}
