package com.medcore.tenancy.api

import com.medcore.platform.security.MedcorePrincipal
import com.medcore.platform.tenancy.MembershipStatus
import com.medcore.platform.tenancy.TenantStatus
import com.medcore.tenancy.service.MembershipDetail
import com.medcore.tenancy.service.TenancyService
import com.medcore.tenancy.service.TenantMembershipResult
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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
