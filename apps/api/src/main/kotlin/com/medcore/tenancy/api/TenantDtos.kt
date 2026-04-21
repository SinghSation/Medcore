package com.medcore.tenancy.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.medcore.platform.tenancy.MembershipRole
import com.medcore.platform.tenancy.MembershipStatus
import com.medcore.platform.tenancy.TenantStatus

/**
 * Wire shapes for the tenancy read surface. Mirrors
 * `packages/schemas/openapi/tenancy/tenancy.yaml` (Rule 02).
 *
 * DTOs only — never JPA entities, never principal objects, never anything
 * broader than the declared contract (Charter §4).
 *
 * `@JsonInclude(ALWAYS)` keeps declared keys present even when the
 * underlying value is null, so the contract shape is stable for clients
 * and for the integration tests (same policy as `MeResponse`).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class TenantSummaryResponse(
    val id: String,
    val slug: String,
    val displayName: String,
    val status: TenantStatus,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class MembershipResponse(
    val membershipId: String,
    val userId: String,
    val role: MembershipRole,
    val status: MembershipStatus,
    val tenant: TenantSummaryResponse,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class MembershipListResponse(
    val items: List<MembershipResponse>,
)
