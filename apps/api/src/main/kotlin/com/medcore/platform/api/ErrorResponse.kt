package com.medcore.platform.api

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Wire shape for error responses. Mirrors the `ErrorEnvelope` schema in
 * `packages/schemas/errors/error-envelope.yaml` (Rule 02).
 *
 * Never carries PHI, stack traces, SQL, or file paths (Rule 01, Rule 02).
 *
 * `details` discipline:
 *
 *   - MUST remain `null` for every emission EXCEPT [ErrorCodes.REQUEST_VALIDATION_FAILED],
 *     whose handler populates `details.validationErrors` with a flat
 *     array of `{field, code}` objects — field names only, never
 *     field values (a rejected patient DOB value would leak PHI).
 *   - MUST NOT be used as a free-form bag for diagnostics, payload
 *     fragments, or ad-hoc key/value context. That pattern is exactly
 *     how PHI and implementation detail leak to clients (Rule 01).
 *   - When a future slice needs structured error context, introduce a
 *     typed detail payload dedicated to that specific error `code`
 *     (one schema per code), add it to `error-envelope.yaml` as a
 *     discriminated union, and regenerate consumers. Do NOT loosen the
 *     existing discipline in passing.
 *
 * `requestId` discipline:
 *
 *   - Every handler MUST populate `requestId` from `MDC[request_id]`
 *     (populated by `RequestIdFilter` in Phase 3F.1). The body
 *     `requestId` MUST equal the response's `X-Request-Id` header
 *     (`HeaderBodyRequestIdParityTest` enforces).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorResponse(
    val code: String,
    val message: String,
    val requestId: String? = null,
    val details: Map<String, Any>? = null,
)

/**
 * Stable error codes emitted by platform and feature modules. Keep
 * this list small and specific; generic fall-backs encourage drift.
 * Every code below maps to exactly one HTTP status class per Phase 3G
 * `GlobalExceptionHandler`. Security codes (`auth.*`, `tenancy.*`)
 * are deliberately non-enumerating — multiple denial reasons may map
 * to the same code so a probing caller cannot distinguish them.
 *
 * **Registry discipline:** this object is a closed registry of the
 * codes Medcore emits on the wire. Introducing a new code REQUIRES,
 * in the same slice:
 *
 *   1. A new `const val` here AND the corresponding enum entry in
 *      `packages/schemas/errors/error-envelope.yaml` §ErrorEnvelope
 *      `code` description.
 *   2. An `@ExceptionHandler` (in this package OR a module-specific
 *      adviser) that maps to the code.
 *   3. Integration-test coverage of the new code in
 *      `GlobalExceptionHandlerIntegrationTest` or module-equivalent.
 *   4. A review-pack callout that the code set is expanding. Large
 *      expansions (e.g., a new code family for a new module)
 *      warrant a new ADR.
 *
 * Renaming or removing a code is a breaking wire-contract change —
 * handle via a superseding ADR, never in-place.
 */
object ErrorCodes {
    // --- 401 ---
    /** Missing / invalid / expired authentication credentials. */
    const val AUTH_UNAUTHENTICATED: String = "auth.unauthenticated"

    // --- 403 ---
    /**
     * Authenticated caller lacks the authority for this action. Emitted
     * by [com.medcore.platform.security.MedcoreAccessDeniedHandler] for
     * Spring `AccessDeniedException`. Message is identical to
     * [TENANT_FORBIDDEN]'s message — callers distinguish the two only
     * by `code`, never by message (no enumeration signal).
     */
    const val AUTH_FORBIDDEN: String = "auth.forbidden"

    /**
     * Tenancy-scoped forbidden. Emitted by
     * [com.medcore.tenancy.context.TenantContextFilter] on header
     * denial and by [com.medcore.tenancy.api.TenancyExceptionHandler]
     * on `TenantAccessDeniedException`.
     */
    const val TENANT_FORBIDDEN: String = "tenancy.forbidden"

    // --- 404 ---
    /**
     * No such route, no such entity, no such resource. Aggregates
     * Spring MVC's "no handler" path AND JPA's `EntityNotFoundException`
     * AND `EmptyResultDataAccessException`.
     */
    const val RESOURCE_NOT_FOUND: String = "resource.not_found"

    // --- 409 ---
    /**
     * State conflict — optimistic locking version mismatch, business-
     * level unique/foreign-key constraint violation. NOT for input
     * validation failures (those map to 422).
     */
    const val RESOURCE_CONFLICT: String = "resource.conflict"

    /**
     * Phase 4A.2: duplicate-patient warning on
     * `POST /api/v1/tenants/{slug}/patients`. Emitted when the
     * `DuplicatePatientDetector` surfaces candidate matches
     * (exact-name + DOB or phonetic-name + DOB) and the caller did
     * NOT set `X-Confirm-Duplicate: true`. `details.candidates`
     * carries `[{patientId, mrn}]` — minimal disclosure, no
     * name/DOB/demographics. Client retries with the header after
     * confirming the patient is genuinely distinct.
     *
     * Distinct code (not `resource.conflict`) because the retry
     * contract differs: clients retry this by adding a header, not
     * by re-fetching state.
     */
    const val CLINICAL_PATIENT_DUPLICATE_WARNING: String =
        "clinical.patient.duplicate_warning"

    // --- 428 ---
    /**
     * Phase 4A.2: `If-Match` header missing on a conditional
     * request (RFC 7232). Medcore requires `If-Match` on
     * `PATCH /api/v1/tenants/{slug}/patients/{id}` and every future
     * PHI-bearing PATCH — blind overwrites of PHI are disallowed.
     * 428 lets the client distinguish "you forgot the header"
     * from "you sent a stale version" (which maps to 409
     * `resource.conflict`).
     */
    const val PRECONDITION_REQUIRED: String = "request.precondition_required"

    // --- 422 ---
    /**
     * Request is syntactically valid but semantically invalid. Bean-
     * validation failures on request bodies, `@Valid`-annotated
     * arguments, check/NOT-NULL constraint violations at the DB layer.
     * Populates `details.validationErrors` with `{field, code}` only.
     */
    const val REQUEST_VALIDATION_FAILED: String = "request.validation_failed"

    /**
     * Tenant context header required by the route was absent. Raised
     * via `TenantContextMissingException` from `TenantContext.require()`.
     * 422 rather than 403 because it is a request-completeness failure,
     * not an authorization decision.
     */
    const val TENANCY_CONTEXT_REQUIRED: String = "tenancy.context.required"

    // --- 5xx ---
    /** Opaque fallback for any uncaught throwable. Never reveals the cause. */
    const val SERVER_ERROR: String = "server.error"
}
