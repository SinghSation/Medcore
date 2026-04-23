package com.medcore.clinical.patient.api

import com.fasterxml.jackson.databind.JsonNode
import com.medcore.clinical.patient.read.GetPatientCommand
import com.medcore.clinical.patient.read.GetPatientHandler
import com.medcore.clinical.patient.write.AddPatientIdentifierCommand
import com.medcore.clinical.patient.write.AddPatientIdentifierHandler
import com.medcore.clinical.patient.write.CreatePatientCommand
import com.medcore.clinical.patient.write.CreatePatientHandler
import com.medcore.clinical.patient.write.PatientIdentifierSnapshot
import com.medcore.clinical.patient.write.PatientSnapshot
import com.medcore.clinical.patient.write.RevokePatientIdentifierCommand
import com.medcore.clinical.patient.write.RevokePatientIdentifierHandler
import com.medcore.clinical.patient.write.UpdatePatientDemographicsCommand
import com.medcore.clinical.patient.write.UpdatePatientDemographicsHandler
import com.medcore.clinical.patient.write.UpdatePatientDemographicsSnapshot
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
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP surface for `clinical.patient` (Phase 4A.2).
 *
 * First real PHI write path in Medcore. Two endpoints ship in 4A.2:
 *
 *   - `POST /api/v1/tenants/{slug}/patients` — create
 *   - `PATCH /api/v1/tenants/{slug}/patients/{patientId}` —
 *     partial demographic update
 *
 * `GET /api/v1/tenants/{slug}/patients/{patientId}` is deliberately
 * NOT shipped in 4A.2 — read-auditing lands with it in 4A.5.
 * Shipping a GET before the audit envelope is a compliance
 * failure (HIPAA §164.312(b) requires activity logs for PHI
 * access).
 *
 * ### Wire contract
 *
 * POST 201 response body: `{data: PatientResponse, requestId}`
 * + `ETag: "<rowVersion>"` header. Location header is NOT emitted
 * because GET isn't available yet.
 *
 * PATCH 200 response body: same envelope. PATCH requires `If-Match`
 * (428 if absent, 409 `resource.conflict|reason=stale_row` if
 * stale). Empty-body PATCH → 422 `request.validation_failed|no_fields`.
 *
 * Duplicate-warning on POST → 409 `clinical.patient.duplicate_warning`
 * with `details.candidates = [{patientId, mrn}]`. Client retries
 * with `X-Confirm-Duplicate: true` after verifying.
 *
 * ### PHI discipline
 *
 * This controller binds PHI into JVM memory. Enforcement:
 *   - No `log.info(body.toString())` — the request body carries
 *     name, DOB, and demographics. `PatientLogPhiLeakageTest`
 *     asserts no handler log line contains any demographic field.
 *   - `toString()` on the command is NOT called by the framework
 *     or by Medcore code. Defensive.
 *   - Error responses NEVER include field values (422 envelope's
 *     `validationErrors` carries `field + code` only — see 3G
 *     `ErrorResponse` KDoc).
 */
@RestController
@RequestMapping("/api/v1/tenants/{slug}/patients")
class PatientController(
    private val createPatientGate: WriteGate<CreatePatientCommand, PatientSnapshot>,
    private val createPatientHandler: CreatePatientHandler,
    private val updatePatientDemographicsGate:
        WriteGate<UpdatePatientDemographicsCommand, UpdatePatientDemographicsSnapshot>,
    private val updatePatientDemographicsHandler: UpdatePatientDemographicsHandler,
    // --- Phase 4A.3 identifier satellite ---
    private val addPatientIdentifierGate:
        WriteGate<AddPatientIdentifierCommand, PatientIdentifierSnapshot>,
    private val addPatientIdentifierHandler: AddPatientIdentifierHandler,
    private val revokePatientIdentifierGate:
        WriteGate<RevokePatientIdentifierCommand, PatientIdentifierSnapshot>,
    private val revokePatientIdentifierHandler: RevokePatientIdentifierHandler,
    // --- Phase 4A.4 read path ---
    private val getPatientGate: ReadGate<GetPatientCommand, PatientSnapshot>,
    private val getPatientHandler: GetPatientHandler,
) {

    /**
     * Read a patient (Phase 4A.4). `PATIENT_READ` required
     * (OWNER, ADMIN, or MEMBER per the 4A.1 role map).
     *
     * First PHI read endpoint in Medcore. Every successful 200
     * emits a `CLINICAL_PATIENT_ACCESSED` audit row INSIDE the
     * read-only transaction (ADR-003 §2 atomicity). 404 and
     * 500 emit nothing (no disclosure occurred). Policy-level
     * denials emit `AUTHZ_READ_DENIED`; filter-level denials
     * (SUSPENDED / not-a-member) emit the existing
     * `tenancy.membership.denied` from `TenantContextFilter`.
     *
     * Response: `ApiResponse<PatientResponse>` + `ETag: "<rowVersion>"`
     * header for clients that want to round-trip a PATCH after.
     * Cross-tenant patientId returns 404 (identical to unknown
     * id) — no existence leak.
     */
    @GetMapping(
        "/{patientId}",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getPatient(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable patientId: UUID,
    ): ResponseEntity<ApiResponse<PatientResponse>> {
        val command = GetPatientCommand(slug = slug, patientId = patientId)
        val context = WriteContext(principal = principal, idempotencyKey = null)
        val snapshot = getPatientGate.apply(command, context) { cmd ->
            getPatientHandler.handle(cmd, context)
        }
        val responseBody = ApiResponse(
            data = PatientResponse.from(snapshot),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
        return ResponseEntity.ok()
            .eTag("\"${snapshot.rowVersion}\"")
            .body(responseBody)
    }

    /**
     * Create a patient (Phase 4A.2). PATIENT_CREATE required
     * (OWNER or ADMIN). Duplicate warning on matching DOB + family
     * name (exact or phonetic) unless `X-Confirm-Duplicate: true`.
     */
    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun createPatient(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @Valid @RequestBody body: CreatePatientRequest,
        @RequestHeader(name = "X-Confirm-Duplicate", required = false, defaultValue = "false")
        confirmDuplicate: Boolean,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<ApiResponse<PatientResponse>> {
        val command = body.toCommand(slug, confirmDuplicate)
        val context = WriteContext(principal = principal, idempotencyKey = idempotencyKey)
        val snapshot = createPatientGate.apply(command, context) { cmd ->
            createPatientHandler.handle(cmd, context)
        }
        val responseBody = ApiResponse(
            data = PatientResponse.from(snapshot),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag("\"${snapshot.rowVersion}\"")
            .body(responseBody)
    }

    /**
     * Partial-update demographics (Phase 4A.2). PATIENT_UPDATE
     * required (OWNER or ADMIN).
     *
     * Requires `If-Match: "<rowVersion>"` (428 if absent; 409
     * `resource.conflict|reason=stale_row` if stale). Body is
     * partial JSON — absent fields stay unchanged, explicit nulls
     * clear nullable columns, values update.
     *
     * Body is bound as [JsonNode] (NOT a typed DTO) because Jackson
     * cannot represent the three-state absent/null/value
     * distinction natively. [UpdatePatientDemographicsRequestMapper]
     * walks the node + builds the command.
     */
    @PatchMapping(
        "/{patientId}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun updateDemographics(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable patientId: UUID,
        @RequestBody body: JsonNode?,
        @RequestHeader(name = "If-Match", required = false) ifMatch: String?,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<ApiResponse<PatientResponse>> {
        val expectedRowVersion = parseIfMatch(ifMatch)
        val command = UpdatePatientDemographicsRequestMapper.toCommand(
            slug = slug,
            patientId = patientId,
            expectedRowVersion = expectedRowVersion,
            body = body,
        )
        val context = WriteContext(principal = principal, idempotencyKey = idempotencyKey)
        val result = updatePatientDemographicsGate.apply(command, context) { cmd ->
            updatePatientDemographicsHandler.handle(cmd, context)
        }
        val responseBody = ApiResponse(
            data = PatientResponse.from(result.snapshot),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
        return ResponseEntity.ok()
            .eTag("\"${result.snapshot.rowVersion}\"")
            .body(responseBody)
    }

    // ========================================================================
    // Phase 4A.3 — patient identifier satellite
    // ========================================================================

    /**
     * Add an external identifier to a patient (Phase 4A.3).
     * `PATIENT_UPDATE` required (OWNER or ADMIN). DB-level UNIQUE
     * on `(patient_id, type, issuer, value)` catches exact-
     * duplicates → 409 `resource.conflict`. V17 RLS WITH CHECK
     * refuses non-OWNER/ADMIN callers at the persistence layer
     * (defense in depth).
     *
     * ### Carry-forward — re-add-after-revoke
     *
     * The UNIQUE constraint counts revoked rows (with non-null
     * `valid_to`). A caller cannot re-add an identifier with
     * the same `(type, issuer, value)` after revoking it.
     * Tracked as a 4A.3 carry-forward; amend to a partial
     * unique index `WHERE valid_to IS NULL` if a pilot workflow
     * demands it.
     */
    @PostMapping(
        "/{patientId}/identifiers",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun addPatientIdentifier(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable patientId: UUID,
        @Valid @RequestBody body: AddPatientIdentifierRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<ApiResponse<PatientIdentifierResponse>> {
        val command = body.toCommand(slug, patientId)
        val context = WriteContext(principal = principal, idempotencyKey = idempotencyKey)
        val snapshot = addPatientIdentifierGate.apply(command, context) { cmd ->
            addPatientIdentifierHandler.handle(cmd, context)
        }
        val responseBody = ApiResponse(
            data = PatientIdentifierResponse.from(snapshot),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag("\"${snapshot.rowVersion}\"")
            .body(responseBody)
    }

    /**
     * Revoke (soft-delete via `valid_to`) an identifier
     * (Phase 4A.3). `PATIENT_UPDATE` required. Idempotent:
     * DELETE on an already-revoked identifier returns 204 with
     * no state change and no audit row. Precedent from 3J.N
     * `DELETE /memberships/{id}`.
     *
     * No `If-Match` header required — revoke is idempotent
     * and lifecycle-transition-scoped, not demographic-edit-
     * scoped. Matches `clinical-write-pattern.md` §7.2 scope
     * clarification (If-Match is a PHI-PATCH concern).
     */
    @DeleteMapping("/{patientId}/identifiers/{identifierId}")
    fun revokePatientIdentifier(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable patientId: UUID,
        @PathVariable identifierId: UUID,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<Void> {
        val command = RevokePatientIdentifierCommand(
            slug = slug,
            patientId = patientId,
            identifierId = identifierId,
        )
        val context = WriteContext(principal = principal, idempotencyKey = idempotencyKey)
        revokePatientIdentifierGate.apply(command, context) { cmd ->
            revokePatientIdentifierHandler.handle(cmd, context)
        }
        return ResponseEntity.noContent().build()
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Parses the RFC 7232 `If-Match` header into a `Long`
     * row-version.
     *
     * Accepted shapes:
     *   - Strong ETag form: `"5"` (double-quoted integer)
     *   - Unquoted integer: `5`
     *
     * 428 on absent; 422 `request.validation_failed|malformed`
     * on unparseable. Wildcard `If-Match: *` is NOT accepted for
     * PHI updates — wildcard semantics mean "any version" which
     * defeats the optimistic-concurrency guarantee.
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
