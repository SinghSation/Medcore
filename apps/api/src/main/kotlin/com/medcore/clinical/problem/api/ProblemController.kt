package com.medcore.clinical.problem.api

import com.fasterxml.jackson.databind.JsonNode
import com.medcore.clinical.problem.read.ListPatientProblemsCommand
import com.medcore.clinical.problem.read.ListPatientProblemsHandler
import com.medcore.clinical.problem.read.ListPatientProblemsResult
import com.medcore.clinical.problem.write.AddProblemCommand
import com.medcore.clinical.problem.write.AddProblemHandler
import com.medcore.clinical.problem.write.ProblemSnapshot
import com.medcore.clinical.problem.write.UpdateProblemCommand
import com.medcore.clinical.problem.write.UpdateProblemHandler
import com.medcore.clinical.problem.write.UpdateProblemOutcome
import com.medcore.platform.api.ApiResponse
import com.medcore.platform.api.PreconditionRequiredException
import com.medcore.platform.observability.MdcKeys
import com.medcore.platform.read.ReadGate
import com.medcore.platform.security.MedcorePrincipal
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteGate
import com.medcore.platform.write.WriteValidationException
import jakarta.validation.Valid
import java.util.UUID
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP surface for `clinical.problem` (Phase 4E.2).
 *
 * Patient-scoped resource — every endpoint nests under
 * `/patients/{patientId}/problems`. Three operations:
 *
 *   - `POST   .../problems`         → 201 ProblemResponse
 *   - `PATCH  .../problems/{id}`    → 200 ProblemResponse
 *   - `GET    .../problems`         → 200 ProblemListResponse
 *
 * No DELETE in 4E.2 — soft-delete via PATCH `status =
 * "ENTERED_IN_ERROR"` is the retraction path (audited as
 * `CLINICAL_PROBLEM_REVOKED`). Resolution via PATCH
 * `status = "RESOLVED"` is a separate clinical-outcome action
 * (audited as `CLINICAL_PROBLEM_RESOLVED`). One PATCH endpoint,
 * three audit actions.
 *
 * ### Authority
 *
 * - POST + PATCH require `PROBLEM_WRITE` (OWNER + ADMIN).
 * - GET requires `PROBLEM_READ` (all three roles — chart-context
 *   surface).
 *
 * ### Wire contract — error surfaces
 *
 * 404 paths (no existence leak — identical bodies):
 *   - unknown patientId
 *   - cross-tenant patientId
 *   - unknown problemId on PATCH
 *   - cross-tenant or cross-patient problemId on PATCH
 *
 * 409 paths (carry `details.reason` per platform 4C.4 contract):
 *   - `stale_row` — PATCH If-Match precondition mismatch
 *   - `problem_terminal` — PATCH on an already-ENTERED_IN_ERROR
 *     row that would actually change anything
 *   - `problem_invalid_transition` — PATCH attempting
 *     RESOLVED → INACTIVE (load-bearing RESOLVED ≠ INACTIVE
 *     invariant; see [com.medcore.clinical.problem.model.ProblemStatus])
 *
 * 422 paths (`request.validation_failed`):
 *   - empty / missing body fields, length violations,
 *     malformed enum tokens, malformed dates,
 *     `abatementDate` < `onsetDate`
 *   - `request.validation_failed|If-Match|wildcard_rejected` —
 *     `If-Match: *` is refused for PHI updates
 *
 * 428 path:
 *   - PATCH without `If-Match` header
 *
 * ### PHI discipline
 *
 * Condition text is PHI when combined with the patient FK.
 * Audit rows never carry the condition text — only closed-enum
 * status / severity tokens (see chunk B audit KDoc + chunk C
 * auditor implementations). Severity, when present, is
 * intentionally NOT in the `add` audit slug (locked behavior:
 * severity is nullable, encoding "UNSPECIFIED" would muddy
 * compliance queries).
 */
@RestController
@RequestMapping("/api/v1/tenants/{slug}/patients/{patientId}/problems")
class ProblemController(
    private val addProblemGate: WriteGate<AddProblemCommand, ProblemSnapshot>,
    private val addProblemHandler: AddProblemHandler,
    private val updateProblemGate: WriteGate<UpdateProblemCommand, UpdateProblemOutcome>,
    private val updateProblemHandler: UpdateProblemHandler,
    private val listPatientProblemsGate:
        ReadGate<ListPatientProblemsCommand, ListPatientProblemsResult>,
    private val listPatientProblemsHandler: ListPatientProblemsHandler,
) {

    /**
     * Add a new problem on a patient (Phase 4E.2). Status is
     * always ACTIVE on insert; lifecycle transitions go through
     * PATCH. Emits `CLINICAL_PROBLEM_ADDED` on 201.
     */
    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun addProblem(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable patientId: UUID,
        @Valid @RequestBody body: AddProblemRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<ApiResponse<ProblemResponse>> {
        val command = body.toCommand(slug, patientId)
        val context = WriteContext(principal = principal, idempotencyKey = idempotencyKey)
        val snapshot = addProblemGate.apply(command, context) { cmd ->
            addProblemHandler.handle(cmd, context)
        }
        val responseBody = ApiResponse(
            data = ProblemResponse.from(snapshot),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag("\"${snapshot.rowVersion}\"")
            .body(responseBody)
    }

    /**
     * Update a problem (Phase 4E.2). Mutable fields:
     * `severity`, `onsetDate`, `abatementDate`, `status`.
     * `conditionText` is intentionally NOT in the patch surface
     * (locked Q7 — immutable post-create).
     *
     * `If-Match` header is REQUIRED — 428 if absent. Stale
     * row_version → 409 `stale_row`. PATCH body is bound as
     * `JsonNode?` so the three-state Patchable semantics
     * (absent / null / value) survive Jackson deserialisation.
     *
     * Status transitions emit different audit actions per the
     * three-way [UpdateProblemOutcome.Kind] dispatch:
     *   - target ∈ {ACTIVE, INACTIVE} → `CLINICAL_PROBLEM_UPDATED`
     *     (covers RESOLVED → ACTIVE recurrence, with
     *     `status_from:RESOLVED|status_to:ACTIVE` preserved in
     *     the audit slug).
     *   - target = RESOLVED → `CLINICAL_PROBLEM_RESOLVED` with
     *     `prior_status:<X>`. Distinct clinical-outcome action.
     *   - target = ENTERED_IN_ERROR → `CLINICAL_PROBLEM_REVOKED`
     *     with `prior_status:<X>`. Soft-delete retraction.
     *   - RESOLVED → INACTIVE: refused → 409
     *     `problem_invalid_transition` (RESOLVED ≠ INACTIVE).
     *   - ENTERED_IN_ERROR → anything (with actual change) →
     *     409 `problem_terminal`.
     */
    @PatchMapping(
        "/{problemId}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun updateProblem(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable patientId: UUID,
        @PathVariable problemId: UUID,
        @RequestBody body: JsonNode?,
        @RequestHeader(name = "If-Match", required = false) ifMatch: String?,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<ApiResponse<ProblemResponse>> {
        val expectedRowVersion = parseIfMatch(ifMatch)
        val command = UpdateProblemRequestMapper.toCommand(
            slug = slug,
            patientId = patientId,
            problemId = problemId,
            expectedRowVersion = expectedRowVersion,
            body = body,
        )
        val context = WriteContext(principal = principal, idempotencyKey = idempotencyKey)
        val outcome = updateProblemGate.apply(command, context) { cmd ->
            updateProblemHandler.handle(cmd, context)
        }
        val responseBody = ApiResponse(
            data = ProblemResponse.from(outcome.snapshot),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
        return ResponseEntity.ok()
            .eTag("\"${outcome.snapshot.rowVersion}\"")
            .body(responseBody)
    }

    /**
     * List all problems for a patient (Phase 4E.2). Returns
     * every status (ACTIVE / INACTIVE / RESOLVED /
     * ENTERED_IN_ERROR) so the audit count matches RLS-allowed
     * visibility; the frontend chart-context surface decides
     * what to show.
     *
     * Emits `CLINICAL_PROBLEM_LIST_ACCESSED` on 200, including
     * for empty lists (a zero-row problem list is still a
     * disclosure event — "we asked and there are none recorded
     * yet").
     */
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listPatientProblems(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable patientId: UUID,
        @org.springframework.web.bind.annotation.RequestParam(name = "pageSize", required = false) pageSize: Int?,
        @org.springframework.web.bind.annotation.RequestParam(name = "cursor", required = false) cursor: String?,
    ): ResponseEntity<ApiResponse<ProblemListResponse>> {
        val pageRequest = com.medcore.platform.read.pagination.PageRequest.fromQueryParams(
            pageSize = pageSize, cursor = cursor,
        )
        val command = ListPatientProblemsCommand(
            slug = slug, patientId = patientId, pageRequest = pageRequest,
        )
        val context = WriteContext(principal = principal, idempotencyKey = null)
        val result = listPatientProblemsGate.apply(command, context) { cmd ->
            listPatientProblemsHandler.handle(cmd, context)
        }
        val responseBody = ApiResponse(
            data = ProblemListResponse.from(result),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
        return ResponseEntity.ok(responseBody)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Parses RFC 7232 `If-Match` for the PATCH precondition.
     * Same shape as `AllergyController.parseIfMatch`. Promotion
     * to a shared platform helper is a future refactor when a
     * fourth controller needs it; for now duplicating mirrors
     * the 4E.1 decision.
     *
     * - Absent → 428 `PreconditionRequiredException`
     * - `*`    → 422 `If-Match|wildcard_rejected`
     * - Non-integer → 422 `If-Match|malformed`
     * - Quoted-integer (`"5"`) and bare-integer (`5`) both
     *   accepted.
     */
    private fun parseIfMatch(header: String?): Long {
        if (header.isNullOrBlank()) {
            throw PreconditionRequiredException(headerName = "If-Match")
        }
        if (header == "*") {
            throw WriteValidationException(field = "If-Match", code = "wildcard_rejected")
        }
        val trimmed = header.trim().removeSurrounding("\"")
        return trimmed.toLongOrNull()
            ?: throw WriteValidationException(field = "If-Match", code = "malformed")
    }
}
