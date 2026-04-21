package com.medcore.identity.api

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * DTO for GET /api/v1/me. Mirrors the `Me` schema in
 * `packages/schemas/openapi/identity/identity.yaml`.
 *
 * `JsonInclude.ALWAYS` emits every declared key on every response, even when
 * the underlying value is null. The OpenAPI schema marks the optional PII
 * fields `nullable: true`, so explicit `null` is the contract-compliant
 * representation and keeps the response shape stable for clients and for
 * the contract-shape integration test.
 *
 * This DTO never carries JPA entities, principal objects, or anything
 * broader than the declared contract (Charter §4, Rule 02).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class MeResponse(
    val userId: String,
    val issuer: String,
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String?,
    val preferredUsername: String?,
    val status: MeStatus,
)

enum class MeStatus {
    ACTIVE,
    DISABLED,
    DELETED,
}
