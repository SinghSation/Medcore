package com.medcore.tenancy.api

import com.medcore.platform.observability.MdcKeys
import com.medcore.platform.security.MedcorePrincipal
import com.medcore.platform.tenancy.MembershipStatus
import com.medcore.platform.tenancy.TenantStatus
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteGate
import com.medcore.platform.write.WriteResponse
import com.medcore.tenancy.service.MembershipDetail
import com.medcore.tenancy.service.TenancyService
import com.medcore.tenancy.service.TenantMembershipResult
import com.medcore.tenancy.write.TenantSnapshot
import com.medcore.tenancy.write.UpdateTenantDisplayNameCommand
import com.medcore.tenancy.write.UpdateTenantDisplayNameHandler
import jakarta.validation.Valid
import org.slf4j.MDC
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Contract: packages/schemas/openapi/tenancy/tenancy.yaml.
//
// Both endpoints require authentication (Spring Security's filter chain on
// /api/** is configured in platform.security.SecurityConfig). Neither
// endpoint currently requires the X-Medcore-Tenant header — it is OPTIONAL
// in this slice; tenancy.context.TenantContextFilter validates it when
// present so future routes can adopt it without reinventing the wheel.
//
// Audit emission lives in the tenancy service, not here (Phase 3C scope
// rule: "never emit audit from controllers"). The controller only maps
// the service's sealed TenantMembershipResult into an HTTP response —
// the denial audit row has already been written by the time the
// exception is thrown.
@RestController
@RequestMapping("/api/v1/tenants")
class TenantsController(
    private val tenancyService: TenancyService,
    private val updateTenantDisplayNameGate: WriteGate<UpdateTenantDisplayNameCommand, TenantSnapshot>,
    private val updateTenantDisplayNameHandler: UpdateTenantDisplayNameHandler,
) {

    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(@AuthenticationPrincipal principal: MedcorePrincipal): MembershipListResponse {
        val memberships = tenancyService
            .listMembershipsFor(principal.userId)
            .filter { it.isVisibleOnList() }
            .map { it.toResponse() }
        return MembershipListResponse(items = memberships)
    }

    @GetMapping("/{slug}/me", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun membershipForCurrentUser(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
    ): MembershipResponse = when (
        val result = tenancyService.findMembershipForCallerBySlug(principal.userId, slug)
    ) {
        is TenantMembershipResult.Granted -> result.detail.toResponse()
        is TenantMembershipResult.Denied -> throw TenantAccessDeniedException(
            "denied userId=${principal.userId} slug=$slug reason=${result.reason.code}",
        )
    }

    /**
     * First concrete write through the Phase 3J.1 WriteGate substrate
     * (Phase 3J.2, ADR-007 §4). Display-name updates only — status
     * changes, slug changes, and deletion remain out of scope until a
     * subsequent slice.
     *
     * Flow: `@Valid` → `UpdateTenantDisplayNameValidator` →
     * `UpdateTenantDisplayNamePolicy` (via `WriteGate`) →
     * `UpdateTenantDisplayNameHandler` (inside the gate's transaction)
     * → `UpdateTenantDisplayNameAuditor.onSuccess` (same transaction) →
     * response. Denial fires `AUTHZ_WRITE_DENIED` via the auditor's
     * `onDenied` path and re-throws; Phase 3G maps the
     * `WriteAuthorizationException` to `403 auth.forbidden`.
     */
    @PatchMapping(
        "/{slug}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun updateTenant(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @Valid @RequestBody body: UpdateTenantRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): WriteResponse<TenantSummaryResponse> {
        val command = UpdateTenantDisplayNameCommand(
            slug = slug,
            // `!!` codifies the @Valid @NotBlank guarantee: if we reach
            // this line, Spring has already rejected null / empty bodies
            // with 422. Trimming here normalises the value before the
            // validator's post-trim-emptiness + control-chars checks.
            displayName = body.displayName!!.trim(),
        )
        val context = WriteContext(
            principal = principal,
            idempotencyKey = idempotencyKey,
        )
        val snapshot = updateTenantDisplayNameGate.apply(command, context) { cmd ->
            updateTenantDisplayNameHandler.handle(cmd)
        }
        return WriteResponse(
            data = TenantSummaryResponse.from(snapshot),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
    }

    private fun MembershipDetail.isVisibleOnList(): Boolean =
        status == MembershipStatus.ACTIVE && tenant.status == TenantStatus.ACTIVE

    private fun MembershipDetail.toResponse(): MembershipResponse = MembershipResponse(
        membershipId = membershipId.toString(),
        userId = userId.toString(),
        role = role,
        status = status,
        tenant = TenantSummaryResponse(
            id = tenant.id.toString(),
            slug = tenant.slug,
            displayName = tenant.displayName,
            status = tenant.status,
        ),
    )
}
