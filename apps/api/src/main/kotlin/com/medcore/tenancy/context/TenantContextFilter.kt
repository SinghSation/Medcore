package com.medcore.tenancy.context

import com.fasterxml.jackson.databind.ObjectMapper
import com.medcore.platform.api.ErrorCodes
import com.medcore.platform.api.ErrorResponse
import com.medcore.platform.security.MedcorePrincipal
import com.medcore.platform.tenancy.TenantMembershipLookup
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

// Reads the optional X-Medcore-Tenant header and, if present, resolves
// the authenticated caller's membership for that slug. On success, the
// resolved membership is stashed in the request-scoped TenantContext;
// on any failure path, the filter writes a 403 with the shared
// ErrorResponse envelope and stops the chain (Rule 01: deny-by-default).
//
// Scope for 3B.1:
//   - Runs on /api/** ONLY.
//   - Runs AFTER Spring Security's filter chain — the principal must
//     already be authenticated; TenantContextFilterRegistration configures
//     the order relative to SecurityFilterAutoConfiguration's filter.
//   - Absence of the header is NOT an error in this slice; no route
//     currently requires header-based tenant context. Future PHI routes
//     that require it will call TenantContext.require() or gate on
//     TenantContext.isSet at the controller.
//
// TODO(phase-3C): emit tenancy.context.set / tenancy.membership.denied
// audit events here once the audit writer lands.
class TenantContextFilter(
    private val tenantContext: TenantContext,
    private val membershipLookup: TenantMembershipLookup,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rawHeader = request.getHeader(HEADER_NAME)
        if (rawHeader.isNullOrBlank()) {
            filterChain.doFilter(request, response)
            return
        }
        val slug = rawHeader.trim()

        val principal = authenticatedPrincipal()
        if (principal == null) {
            // /api/** is already behind authentication; if we somehow reach
            // this filter without an authenticated principal, defer to the
            // security chain to produce the 401. Don't mint our own.
            filterChain.doFilter(request, response)
            return
        }

        val resolved = membershipLookup.resolve(principal.userId, slug)
        if (resolved == null || !resolved.isActive) {
            writeForbidden(response)
            return
        }

        tenantContext.set(resolved)
        filterChain.doFilter(request, response)
    }

    private fun authenticatedPrincipal(): MedcorePrincipal? {
        val authentication = SecurityContextHolder.getContext().authentication ?: return null
        val principal = authentication.principal
        return principal as? MedcorePrincipal
    }

    private fun writeForbidden(response: HttpServletResponse) {
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(
            response.outputStream,
            ErrorResponse(
                code = ErrorCodes.TENANT_FORBIDDEN,
                // Uniform message — no enumeration signal (unknown slug
                // vs. not-a-member vs. suspended all return the same text).
                message = "Access to the requested tenant is denied.",
            ),
        )
    }

    companion object {
        const val HEADER_NAME: String = "X-Medcore-Tenant"
    }
}
