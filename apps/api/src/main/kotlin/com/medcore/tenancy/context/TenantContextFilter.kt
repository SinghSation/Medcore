package com.medcore.tenancy.context

import com.fasterxml.jackson.databind.ObjectMapper
import com.medcore.platform.api.ErrorCodes
import com.medcore.platform.api.ErrorResponse
import com.medcore.platform.audit.ActorType
import com.medcore.platform.audit.AuditAction
import com.medcore.platform.audit.AuditEventCommand
import com.medcore.platform.audit.AuditOutcome
import com.medcore.platform.audit.AuditWriter
import com.medcore.platform.observability.MdcKeys
import com.medcore.platform.security.MedcorePrincipal
import com.medcore.platform.tenancy.MembershipStatus
import com.medcore.platform.tenancy.ResolvedMembership
import com.medcore.platform.tenancy.TenantMembershipLookup
import com.medcore.platform.tenancy.TenantStatus
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.slf4j.MDC
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
// Audit emission (ADR-003 §7):
//   - tenancy.context.set on successful header resolution.
//   - tenancy.membership.denied on header-driven denial. A short,
//     non-enumerating reason code disambiguates for investigators
//     without leaking into the 403 response body (Rule 01).
//   Both writes are synchronous; a failed audit surfaces as a 500 and
//   prevents the 403 or the success path from proceeding (ADR-003 §2).
//
// Scope:
//   - Runs on /api/** ONLY.
//   - Runs AFTER Spring Security's filter chain — the principal must
//     already be authenticated; TenantContextFilterRegistration
//     configures the order relative to
//     SecurityFilterAutoConfiguration's filter.
//   - Absence of the header is NOT an error in this slice; no route
//     currently requires header-based tenant context. Future PHI routes
//     that require it will call TenantContext.require() or gate on
//     TenantContext.isSet at the controller.
class TenantContextFilter(
    private val tenantContext: TenantContext,
    private val membershipLookup: TenantMembershipLookup,
    private val objectMapper: ObjectMapper,
    private val auditWriter: AuditWriter,
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
            // security chain to produce the 401. Don't mint our own and
            // don't audit — the auth entry point owns login.failure.
            filterChain.doFilter(request, response)
            return
        }

        val resolved = membershipLookup.resolve(principal.userId, slug)
        val denialReason = resolved.denialReason()
        if (denialReason != null) {
            auditDenied(principal.userId, resolved, denialReason)
            writeForbidden(response)
            return
        }

        // resolved is non-null and active here.
        requireNotNull(resolved)
        tenantContext.set(resolved)
        auditContextSet(principal.userId, resolved)
        // Populate MDC with tenant_id for structured logging and any
        // downstream audit emission on this request thread. Cleared in
        // the `finally` so the value never leaks to pooled threads
        // after the request completes.
        MDC.put(MdcKeys.TENANT_ID, resolved.tenantId.toString())
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MdcKeys.TENANT_ID)
        }
    }

    private fun ResolvedMembership?.denialReason(): HeaderDenialReason? {
        if (this == null) return HeaderDenialReason.SLUG_UNKNOWN_OR_NOT_A_MEMBER
        if (tenantStatus != TenantStatus.ACTIVE) return HeaderDenialReason.TENANT_INACTIVE
        if (status != MembershipStatus.ACTIVE) return HeaderDenialReason.MEMBERSHIP_INACTIVE
        return null
    }

    private fun auditDenied(
        actorId: UUID,
        resolved: ResolvedMembership?,
        reason: HeaderDenialReason,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.TENANCY_MEMBERSHIP_DENIED,
                actorType = ActorType.USER,
                actorId = actorId,
                tenantId = resolved?.tenantId,
                outcome = AuditOutcome.DENIED,
                reason = reason.code,
            ),
        )
    }

    private fun auditContextSet(actorId: UUID, resolved: ResolvedMembership) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.TENANCY_CONTEXT_SET,
                actorType = ActorType.USER,
                actorId = actorId,
                tenantId = resolved.tenantId,
                outcome = AuditOutcome.SUCCESS,
                reason = REASON_HEADER,
            ),
        )
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

    private enum class HeaderDenialReason(val code: String) {
        SLUG_UNKNOWN_OR_NOT_A_MEMBER("header_slug_unknown_or_not_member"),
        TENANT_INACTIVE("header_tenant_inactive"),
        MEMBERSHIP_INACTIVE("header_membership_inactive"),
    }

    companion object {
        const val HEADER_NAME: String = "X-Medcore-Tenant"
        private const val REASON_HEADER: String = "via_header"
    }
}
