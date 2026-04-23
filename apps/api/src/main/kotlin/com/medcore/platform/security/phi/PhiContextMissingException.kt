package com.medcore.platform.security.phi

/**
 * Thrown by [PhiSessionContext.applyFromRequest] when it is
 * invoked without an established [PhiRequestContext] on the
 * current thread (Phase 4A.0, 4A design decisions §9.6 + §9.3).
 *
 * ### Why 500, not 401
 *
 * This is a **programmer error**, not a user error. A user with
 * no authentication gets 401 at the Spring Security filter chain;
 * they never reach a clinical service. A user with authentication
 * but no tenant context gets 403 at
 * [com.medcore.tenancy.context.TenantContextFilter] (when a route
 * requires tenant context). Only code paths that bypass both
 * filters — background jobs, misconfigured scheduled tasks — can
 * reach a `@Transactional` clinical method without an
 * established [PhiRequestContext]. That represents a bug the
 * developer must fix, not an auth failure.
 *
 * ### Loud failure is mandatory
 *
 * The 4A.0 design decision (pressure-test refinement) is:
 * **silent no-op is a potential data-leak.** A PHI read without
 * RLS GUCs set would return every row in the clinical schema
 * (RLS fails closed, so in practice returns zero rows — but the
 * absence-of-filter is still architecturally wrong). Throwing
 * loudly at the missing-context boundary ensures developers fix
 * the root cause rather than working around it.
 *
 * ### Mapping
 *
 * [com.medcore.platform.api.GlobalExceptionHandler.onUncaught]
 * catches this (via the `Throwable` fallback) and emits the
 * standard 500 `server.error` envelope. The exception message is
 * logged for operator debugging but NEVER echoed in the response
 * body (Rule 01).
 */
class PhiContextMissingException(message: String) : RuntimeException(message)
