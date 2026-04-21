package com.medcore.tenancy.api

import com.medcore.platform.security.MedcorePrincipal
import com.medcore.platform.tenancy.MembershipStatus
import com.medcore.platform.tenancy.TenantStatus
import com.medcore.tenancy.service.MembershipDetail
import com.medcore.tenancy.service.TenancyService
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
// Only ACTIVE memberships on ACTIVE tenants are returned from the list
// endpoint or resolved on the per-slug /me endpoint — everything else is
// indistinguishable 403 by design (no enumeration signal).
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
    ): MembershipResponse {
        val detail = tenancyService.findMembershipBySlug(principal.userId, slug)
            ?: throw TenantAccessDeniedException(
                "no membership for userId=${principal.userId} slug=$slug",
            )
        if (!detail.isAccessible()) {
            throw TenantAccessDeniedException(
                "membership not accessible for userId=${principal.userId} slug=$slug " +
                    "membershipStatus=${detail.status} tenantStatus=${detail.tenant.status}",
            )
        }
        return detail.toResponse()
    }

    private fun MembershipDetail.isVisibleOnList(): Boolean = isAccessible()

    private fun MembershipDetail.isAccessible(): Boolean =
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
