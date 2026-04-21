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
 *   - MUST remain `null` in every emission shipped in Phase 3B.1 (and in
 *     any follow-up slice that has not landed a typed schema for its
 *     errors).
 *   - MUST NOT be used as a free-form bag for diagnostics, payload
 *     fragments, or ad-hoc key/value context. That pattern is exactly
 *     how PHI and implementation detail leak to clients (Rule 01).
 *   - When a future slice needs structured error context, introduce a
 *     typed detail payload dedicated to that specific error `code`
 *     (one schema per code), add it to `error-envelope.yaml` as a
 *     discriminated union, and regenerate consumers. Do NOT loosen the
 *     existing discipline in passing.
 *
 * `tenancy.forbidden` intentionally emits `details = null`.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorResponse(
    val code: String,
    val message: String,
    val requestId: String? = null,
    val details: Map<String, Any>? = null,
)

/**
 * Stable error codes emitted by platform and feature modules. Keep this
 * list small and specific; generic fall-backs encourage drift.
 */
object ErrorCodes {
    const val TENANT_FORBIDDEN: String = "tenancy.forbidden"
}
