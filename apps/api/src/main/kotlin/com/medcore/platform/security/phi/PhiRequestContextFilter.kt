package com.medcore.platform.security.phi

import com.medcore.platform.security.MedcorePrincipal
import com.medcore.tenancy.context.TenantContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Resolves and stores the per-request [PhiRequestContext] for
 * PHI-bearing paths (Phase 4A.0).
 *
 * ### Ordering contract (NORMATIVE — 4A.0 refinement #2)
 *
 * Runs:
 *   - AFTER Spring Security's authentication filter chain —
 *     `SecurityContextHolder` must hold an authenticated
 *     [MedcorePrincipal].
 *   - AFTER [com.medcore.tenancy.context.TenantContextFilter] —
 *     the request-scoped [TenantContext] must already be resolved
 *     from the `X-Medcore-Tenant` header.
 *   - BEFORE any controller dispatch.
 *   - BEFORE any `@Transactional` boundary opens (every
 *     `@Transactional` method in clinical code relies on this
 *     filter having populated the holder).
 *
 * Registered in [SecurityConfig] with
 * `order = SecurityProperties.DEFAULT_FILTER_ORDER + 20` — ten
 * slots after `TenantContextFilter` (which is at `+10`). Future
 * filter additions must respect the ordering invariant; the
 * integration test asserts the populated-before-controller
 * property directly.
 *
 * ### Lifecycle — finally-block clearing (4A.0 refinement #1)
 *
 * The filter uses `try { chain.doFilter(...); } finally {
 * holder.clear(); }` regardless of whether the context was
 * populated. Reasons:
 *
 * - Spring's request-scoped bean cleanup is not guaranteed in
 *   edge cases (async error dispatch, misconfigured servlet).
 *   Explicit clear in `finally` is belt-and-braces defence.
 * - Thread-pool reuse: if the servlet container pools worker
 *   threads (Tomcat does), a non-cleared ThreadLocal leaks to
 *   the next request on the same thread. Catastrophic PHI leak
 *   potential (cross-request user/tenant bleed).
 *
 * ### No-op semantics (4A.0 design decision §9.6 + §9.2)
 *
 * Filter is a no-op — does NOT populate [PhiRequestContextHolder]
 * — when:
 *
 * - No authenticated principal (e.g., `/actuator/health`,
 *   unauthenticated error paths).
 * - No resolved [TenantContext] (e.g., `/api/v1/me`, routes
 *   that do NOT require the `X-Medcore-Tenant` header).
 *
 * The no-op path is important: clinical routes REQUIRE both
 * principal and tenant; non-clinical routes MUST continue to
 * function without PHI context. Downstream services that need
 * PHI context call [PhiSessionContext.applyFromRequest], which
 * throws [PhiContextMissingException] → 500 (programmer error)
 * if invoked on a non-PHI path.
 */
class PhiRequestContextFilter(
    private val phiRequestContextHolder: PhiRequestContextHolder,
    private val tenantContext: TenantContext,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            val principal = authenticatedPrincipal()
            val resolvedTenantId = tenantContext.current()?.tenantId
            if (principal != null && resolvedTenantId != null) {
                // Both halves of PHI context are present — populate.
                // Partial-context (one present, other missing) is
                // rejected structurally by not populating at all;
                // PhiRequestContext's non-nullable fields make it
                // impossible to construct a half-filled instance
                // (4A.0 refinement #4).
                phiRequestContextHolder.set(
                    PhiRequestContext(
                        userId = principal.userId,
                        tenantId = resolvedTenantId,
                    ),
                )
            }
            // Either no authentication, no tenant, or both — leave
            // the holder empty. Non-PHI routes continue to work;
            // PHI routes that reach a clinical service will fire
            // PhiContextMissingException at applyFromRequest time.
            filterChain.doFilter(request, response)
        } finally {
            // Unconditional clear (4A.0 refinement #1). Even if
            // the filter was a no-op, clearing defends against
            // stale ThreadLocal state from a prior request on the
            // same pooled thread.
            phiRequestContextHolder.clear()
        }
    }

    private fun authenticatedPrincipal(): MedcorePrincipal? {
        val auth = SecurityContextHolder.getContext().authentication
        if (auth == null || !auth.isAuthenticated) return null
        return auth.principal as? MedcorePrincipal
    }
}
