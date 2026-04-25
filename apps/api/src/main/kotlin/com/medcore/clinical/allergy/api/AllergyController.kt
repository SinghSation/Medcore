package com.medcore.clinical.allergy.api

import com.fasterxml.jackson.databind.JsonNode
import com.medcore.clinical.allergy.read.ListPatientAllergiesCommand
import com.medcore.clinical.allergy.read.ListPatientAllergiesHandler
import com.medcore.clinical.allergy.read.ListPatientAllergiesResult
import com.medcore.clinical.allergy.write.AddAllergyCommand
import com.medcore.clinical.allergy.write.AddAllergyHandler
import com.medcore.clinical.allergy.write.AllergySnapshot
import com.medcore.clinical.allergy.write.UpdateAllergyCommand
import com.medcore.clinical.allergy.write.UpdateAllergyHandler
import com.medcore.clinical.allergy.write.UpdateAllergyOutcome
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
 * HTTP surface for `clinical.allergy` (Phase 4E.1).
 *
 * Patient-scoped resource — every endpoint nests under
 * `/patients/{patientId}/allergies`. Three operations:
 *
 *   - `POST   .../allergies`            → 201 AllergyResponse
 *   - `PATCH  .../allergies/{id}`       → 200 AllergyResponse
 *   - `GET    .../allergies`            → 200 AllergyListResponse
 *
 * No DELETE in 4E.1 — soft-delete via PATCH `status =
 * "ENTERED_IN_ERROR"` is the retraction path (audited as
 * `CLINICAL_ALLERGY_REVOKED`). Locked plan: every status
 * transition flows through the same PATCH endpoint.
 *
 * ### Authority
 *
 * - POST + PATCH require `ALLERGY_WRITE` (OWNER + ADMIN).
 * - GET requires `ALLERGY_READ` (all three roles — the banner
 *   is a clinical-safety surface).
 *
 * ### Wire contract — error surfaces
 *
 * 404 paths (no existence leak — identical bodies):
 *   - unknown patientId
 *   - cross-tenant patientId
 *   - unknown allergyId on PATCH
 *   - cross-tenant or cross-patient allergyId on PATCH
 *
 * 409 paths (carry `details.reason` per platform 4C.4 contract):
 *   - `stale_row` — PATCH If-Match precondition mismatch
 *   - `allergy_terminal` — PATCH on an already-ENTERED_IN_ERROR
 *     row that would actually change anything
 *
 * 422 paths (`request.validation_failed`):
 *   - empty / missing body fields, length violations,
 *     malformed enum tokens, malformed dates
 *   - `request.validation_failed|If-Match|wildcard_rejected` —
 *     `If-Match: *` is refused for PHI updates
 *
 * 428 path:
 *   - PATCH without `If-Match` header
 *
 * ### PHI discipline
 *
 * Substance text and reaction text are PHI when combined with
 * the patient FK. The controller binds them into JVM memory
 * but never logs them: no `log.info(body.toString())`, no
 * structured-log line carries the request body. Audit rows
 * never carry the substance / reaction values either — only
 * closed-enum severity / status tokens (see chunk B audit
 * KDoc + chunk C auditor implementations).
 */
@RestController
@RequestMapping("/api/v1/tenants/{slug}/patients/{patientId}/allergies")
class AllergyController(
    private val addAllergyGate: WriteGate<AddAllergyCommand, AllergySnapshot>,
    private val addAllergyHandler: AddAllergyHandler,
    private val updateAllergyGate: WriteGate<UpdateAllergyCommand, UpdateAllergyOutcome>,
    private val updateAllergyHandler: UpdateAllergyHandler,
    private val listPatientAllergiesGate:
        ReadGate<ListPatientAllergiesCommand, ListPatientAllergiesResult>,
    private val listPatientAllergiesHandler: ListPatientAllergiesHandler,
) {

    /**
     * Add a new allergy on a patient (Phase 4E.1). Status is
     * always ACTIVE on insert; lifecycle transitions go through
     * PATCH. Emits `CLINICAL_ALLERGY_ADDED` on 201.
     */
    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun addAllergy(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable patientId: UUID,
        @Valid @RequestBody body: AddAllergyRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<ApiResponse<AllergyResponse>> {
        val command = body.toCommand(slug, patientId)
        val context = WriteContext(principal = principal, idempotencyKey = idempotencyKey)
        val snapshot = addAllergyGate.apply(command, context) { cmd ->
            addAllergyHandler.handle(cmd, context)
        }
        val responseBody = ApiResponse(
            data = AllergyResponse.from(snapshot),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag("\"${snapshot.rowVersion}\"")
            .body(responseBody)
    }

    /**
     * Update an allergy (Phase 4E.1). Mutable fields:
     * `severity`, `reactionText`, `onsetDate`, `status`.
     * `substanceText` is intentionally NOT in the patch surface
     * (locked Q2 — immutable post-create).
     *
     * `If-Match` header is REQUIRED — 428 if absent. Stale
     * row_version → 409 `stale_row`. PATCH body is bound as
     * `JsonNode?` so the three-state Patchable semantics
     * (absent / null / value) survive Jackson deserialisation.
     *
     * Status transitions emit different audit actions:
     *   - ACTIVE ↔ INACTIVE → `CLINICAL_ALLERGY_UPDATED` with
     *     `status_from`/`status_to` in the reason slug.
     *   - any → `ENTERED_IN_ERROR` → `CLINICAL_ALLERGY_REVOKED`
     *     with `prior_status` in the reason slug.
     *   - `ENTERED_IN_ERROR` → anything (with actual change) →
     *     409 `allergy_terminal`.
     */
    @PatchMapping(
        "/{allergyId}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun updateAllergy(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable patientId: UUID,
        @PathVariable allergyId: UUID,
        @RequestBody body: JsonNode?,
        @RequestHeader(name = "If-Match", required = false) ifMatch: String?,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<ApiResponse<AllergyResponse>> {
        val expectedRowVersion = parseIfMatch(ifMatch)
        val command = UpdateAllergyRequestMapper.toCommand(
            slug = slug,
            patientId = patientId,
            allergyId = allergyId,
            expectedRowVersion = expectedRowVersion,
            body = body,
        )
        val context = WriteContext(principal = principal, idempotencyKey = idempotencyKey)
        val outcome = updateAllergyGate.apply(command, context) { cmd ->
            updateAllergyHandler.handle(cmd, context)
        }
        val responseBody = ApiResponse(
            data = AllergyResponse.from(outcome.snapshot),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
        return ResponseEntity.ok()
            .eTag("\"${outcome.snapshot.rowVersion}\"")
            .body(responseBody)
    }

    /**
     * List all allergies for a patient (Phase 4E.1). Returns
     * every status (ACTIVE / INACTIVE / ENTERED_IN_ERROR) so
     * the audit count matches RLS-allowed visibility; the
     * frontend filters to ACTIVE for the banner / shows all
     * for the management view.
     *
     * Emits `CLINICAL_ALLERGY_LIST_ACCESSED` on 200, including
     * for empty lists (a zero-row banner is still a
     * disclosure event — "we asked and there are none recorded
     * yet").
     */
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listPatientAllergies(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable patientId: UUID,
    ): ResponseEntity<ApiResponse<AllergyListResponse>> {
        val command = ListPatientAllergiesCommand(slug = slug, patientId = patientId)
        val context = WriteContext(principal = principal, idempotencyKey = null)
        val result = listPatientAllergiesGate.apply(command, context) { cmd ->
            listPatientAllergiesHandler.handle(cmd, context)
        }
        val responseBody = ApiResponse(
            data = AllergyListResponse.from(result),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
        return ResponseEntity.ok(responseBody)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Parses RFC 7232 `If-Match` for the PATCH precondition.
     * Mirrors `PatientController.parseIfMatch` byte-for-byte
     * (would belong in a shared platform helper if a third
     * controller needed it; for now duplicated to avoid a
     * premature shared-package extraction).
     *
     * - Absent → 428 `PreconditionRequiredException`
     * - `*`    → 422 `If-Match|wildcard_rejected`
     * - Non-integer → 422 `If-Match|malformed`
     * - Quoted-integer (`"5"`) and bare-integer (`5`) both accepted
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
