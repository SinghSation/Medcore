package com.medcore.tenancy.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.medcore.platform.tenancy.MembershipRole
import com.medcore.platform.tenancy.MembershipStatus
import com.medcore.platform.tenancy.TenantStatus
import com.medcore.tenancy.write.MembershipSnapshot
import com.medcore.tenancy.write.TenantSnapshot
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

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
) {
    companion object {
        /**
         * Adapts the write-path [TenantSnapshot] (Phase 3J.2) to the
         * outbound wire shape. Same structure as the read-path
         * projection — clients see one `TenantSummaryResponse` shape
         * regardless of whether the response came from a GET or a
         * PATCH. Kept here (rather than on the snapshot) so the
         * write package depends on API shapes, not the other way
         * round.
         */
        fun from(snapshot: TenantSnapshot): TenantSummaryResponse =
            TenantSummaryResponse(
                id = snapshot.id.toString(),
                slug = snapshot.slug,
                displayName = snapshot.displayName,
                status = snapshot.status,
            )
    }
}

/**
 * Request body for `PATCH /api/v1/tenants/{slug}` (Phase 3J.2).
 *
 * `@NotBlank` + `@Size(max = 200)` cover the annotation-expressible
 * rules; Phase 3G's `GlobalExceptionHandler` maps violations to
 * 422 `request.validation_failed` with
 * `details.validationErrors = [{field, code}]`. Domain checks that
 * annotations cannot express (post-trim emptiness, control-character
 * rejection) live in `UpdateTenantDisplayNameValidator` and throw
 * `WriteValidationException` for the same 422 envelope.
 *
 * [displayName] is typed `String?` so a missing field in the JSON
 * body lands here as `null` and trips `@NotBlank` → 422 (rather
 * than falling through to Jackson's default 400 path that Phase 3G
 * leaves unnormalised). By the time the controller reads the value,
 * Spring's `@Valid` has guaranteed it is non-null — the `!!` in
 * the handler codifies that invariant.
 */
data class UpdateTenantRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val displayName: String?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class MembershipResponse(
    val membershipId: String,
    val userId: String,
    val role: MembershipRole,
    val status: MembershipStatus,
    val tenant: TenantSummaryResponse,
) {
    companion object {
        /**
         * Adapts the write-path [MembershipSnapshot] (Phase 3J.3) to
         * the outbound wire shape. Same shape the read-path produces
         * so clients see one `MembershipResponse` regardless of
         * whether the response came from a GET or a POST.
         */
        fun from(snapshot: MembershipSnapshot): MembershipResponse =
            MembershipResponse(
                membershipId = snapshot.id.toString(),
                userId = snapshot.userId.toString(),
                role = snapshot.role,
                status = snapshot.status,
                tenant = TenantSummaryResponse(
                    id = snapshot.tenantId.toString(),
                    slug = snapshot.tenantSlug,
                    displayName = snapshot.tenantDisplayName,
                    status = snapshot.tenantStatus,
                ),
            )
    }
}

@JsonInclude(JsonInclude.Include.ALWAYS)
data class MembershipListResponse(
    val items: List<MembershipResponse>,
)

/**
 * Request body for `POST /api/v1/tenants/{slug}/memberships`
 * (Phase 3J.3).
 *
 * Both fields nullable on the wire shape so missing fields are
 * caught by `@NotNull` → 422 (same 3G envelope), not Jackson's
 * deferred 400 path. By the time the controller reads the values,
 * Spring's `@Valid` has guaranteed they are non-null.
 *
 * [role] is deserialised from a JSON string into the
 * [MembershipRole] enum; invalid values produce a Jackson
 * deserialisation failure → 400 (Phase 3G non-normalised path).
 * A follow-on slice may tighten this via a custom deserialiser
 * that returns 422; tracked as a carry-forward.
 */
data class InviteMembershipRequest(
    @field:NotNull
    val userId: UUID?,
    @field:NotNull
    val role: MembershipRole?,
)

/**
 * Request body for
 * `PATCH /api/v1/tenants/{slug}/memberships/{membershipId}`
 * (Phase 3J.N). Only `role` is mutable on an existing membership
 * via this endpoint — status transitions use the dedicated
 * DELETE / (future) reinstate endpoints.
 */
data class UpdateMembershipRoleRequest(
    @field:NotNull
    val role: MembershipRole?,
)
