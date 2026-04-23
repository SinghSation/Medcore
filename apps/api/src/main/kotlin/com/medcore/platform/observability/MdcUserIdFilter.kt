package com.medcore.platform.observability

import com.medcore.platform.security.MedcorePrincipal
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Populates [MdcKeys.USER_ID] in MDC once the Spring Security filter
 * chain has resolved the authenticated principal.
 *
 * Why a dedicated filter (instead of population inside
 * [com.medcore.platform.security.MedcoreJwtAuthenticationConverter]):
 *
 *   - The JWT converter's job is to produce an `Authentication`
 *     object. Coupling it to MDC / logging concerns blurs layers
 *     and makes unit-testing the converter harder (MDC is a
 *     thread-local side-effect channel).
 *   - Filter-based population runs in a predictable slot and is
 *     trivially testable end-to-end.
 *
 * Ordering:
 *   - Registered at `SecurityProperties.DEFAULT_FILTER_ORDER + 5`
 *     (after Spring Security, before [com.medcore.tenancy.context.TenantContextFilter]
 *     at `+10`), so the tenancy filter's audit writes and any
 *     downstream logs see the user id in MDC.
 *
 * Scope:
 *   - Runs on every `/api/` request. Anonymous / unauthenticated
 *     paths (actuator health, `/error`) do not match the URL
 *     pattern; if such a filter ever gets exercised without an
 *     authentication, the MDC key is simply not populated (no-op).
 *
 * MDC lifecycle:
 *   - [MdcKeys.USER_ID] is set before `chain.doFilter` and cleared
 *     in a `finally` so the value never leaks to pooled threads.
 */
class MdcUserIdFilter : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        // Phase 4A.5 — extend coverage to `/fhir/r4/**` so MDC
        // `user_id` is populated for FHIR-native requests,
        // keeping log correlation symmetric with /api/** routes.
        val uri = request.requestURI
        return !uri.startsWith("/api/") && !uri.startsWith("/fhir/")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val userId = currentUserId()
        if (userId != null) {
            MDC.put(MdcKeys.USER_ID, userId)
        }
        try {
            filterChain.doFilter(request, response)
        } finally {
            if (userId != null) {
                MDC.remove(MdcKeys.USER_ID)
            }
        }
    }

    private fun currentUserId(): String? {
        val auth = SecurityContextHolder.getContext().authentication ?: return null
        val principal = auth.principal as? MedcorePrincipal ?: return null
        return principal.userId.toString()
    }
}
