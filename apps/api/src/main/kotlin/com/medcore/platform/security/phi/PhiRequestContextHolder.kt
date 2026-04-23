package com.medcore.platform.security.phi

import org.springframework.stereotype.Component

/**
 * ThreadLocal-backed holder for the current request's
 * [PhiRequestContext] (Phase 4A.0).
 *
 * ### Why ThreadLocal (not request-scoped Spring bean)
 *
 * Request-scoped Spring beans (`@Scope(SCOPE_REQUEST)`) rely on
 * Spring's request-attribute machinery, which requires a
 * `DispatcherServlet`-aware context and works only for HTTP
 * request threads. PHI execution needs:
 *
 * - Explicit set/clear lifecycle visible at the call site
 *   (finally block in [PhiRequestContextFilter]).
 * - A future async-propagation story compatible with SLF4J
 *   MDC patterns (snapshot + restore on thread handoff).
 * - Usability from non-HTTP entry points (scheduled jobs) once
 *   those arrive in Phase 4F+.
 *
 * ThreadLocal gives all three with one mechanism.
 *
 * ### Async propagation — IMPORTANT (4A.0 refinement #5)
 *
 * ThreadLocal does NOT propagate across thread boundaries. A
 * `CompletableFuture.runAsync(...)` or `@Async` method
 * invocation runs on a different thread and sees an empty
 * holder. Phase 4A does not yet have async PHI paths, but when
 * they arrive:
 *
 * - Capture snapshot on the calling thread:
 *     `val snapshot = phiRequestContextHolder.get()`
 * - Restore in the async thread BEFORE any PHI access:
 *     `snapshot?.let { phiRequestContextHolder.set(it) }`
 * - Clear in a `finally` block on the async thread.
 *
 * Same pattern as `MDCContext` for structured logging. A
 * dedicated helper (`PhiContextPropagator`) may land with the
 * first async-PHI slice if we find we're repeating the
 * snapshot/restore ceremony.
 *
 * ### Lifecycle contract
 *
 * Callers MUST pair [set] with [clear]. [PhiRequestContextFilter]
 * enforces this for HTTP paths via try/finally; manual callers
 * (future non-HTTP paths) are responsible for the symmetric
 * pairing.
 */
@Component
class PhiRequestContextHolder {

    private val threadLocal: ThreadLocal<PhiRequestContext> = ThreadLocal()

    /**
     * Stores [context] on the current thread. Typically invoked by
     * [PhiRequestContextFilter] once per request; future async
     * paths may call this directly after capturing a snapshot on
     * the dispatching thread.
     */
    fun set(context: PhiRequestContext) {
        threadLocal.set(context)
    }

    /**
     * Returns the current thread's [PhiRequestContext], or `null`
     * if none has been established. A non-null return guarantees
     * both fields ([PhiRequestContext.userId],
     * [PhiRequestContext.tenantId]) are populated — the data
     * class's non-nullable fields enforce this at construction.
     */
    fun get(): PhiRequestContext? = threadLocal.get()

    /**
     * Clears the holder for the current thread. MUST be called in
     * a `finally` block paired with the corresponding [set], so
     * subsequent requests on the same (pooled) thread cannot read
     * stale context.
     */
    fun clear() {
        threadLocal.remove()
    }
}
