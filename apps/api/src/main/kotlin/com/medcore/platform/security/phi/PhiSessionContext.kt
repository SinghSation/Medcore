package com.medcore.platform.security.phi

import com.medcore.platform.persistence.TenancySessionContext
import org.springframework.stereotype.Component

/**
 * Bridges [PhiRequestContextHolder] to the RLS GUC layer
 * ([TenancySessionContext]) inside an active Spring transaction
 * (Phase 4A.0).
 *
 * ### Canonical invocation site
 *
 * Every `@Transactional` method in `com.medcore.clinical..service`
 * MUST call [applyFromRequest] as its first line. Enforced by
 * ArchUnit Rule 13 (`ClinicalDisciplineArchTest`). Forgetting
 * this call means the service's SQL queries run without
 * RLS-gating GUCs set, which PostgreSQL treats as "GUC = NULL"
 * → every RLS policy fails closed → zero rows visible. The
 * symptom would be "I query patients and get nothing," which is
 * an annoying debugging experience; loud failure at the boundary
 * is much kinder.
 *
 * ### Invariant
 *
 * - Caller is inside an active Spring transaction (enforced by
 *   [TenancySessionContext.apply]).
 * - [PhiRequestContextHolder] has been populated by
 *   [PhiRequestContextFilter] (HTTP path) or by a manual
 *   establish-context call (future async paths).
 *
 * If either invariant fails: [PhiContextMissingException] or the
 * underlying `TenancySessionContext` `check()` fires. Both map
 * to 500 via Phase 3G's `onUncaught` handler — they represent
 * programmer errors, not user errors.
 *
 * ### Why not just pass the UUIDs directly?
 *
 * Signature discipline. Clinical service methods take domain
 * commands (e.g., `CreatePatientCommand`); threading
 * `(userId, tenantId)` through every method signature dilutes
 * the commands and adds ceremony. The holder pattern lets
 * services call `phiSessionContext.applyFromRequest()` as a
 * one-liner and get back to domain logic.
 */
@Component
class PhiSessionContext(
    private val tenancySessionContext: TenancySessionContext,
    private val phiRequestContextHolder: PhiRequestContextHolder,
) {

    /**
     * Reads the current thread's [PhiRequestContext] and applies
     * the RLS GUCs. Must be invoked inside an active `@Transactional`
     * method.
     *
     * @throws PhiContextMissingException if no [PhiRequestContext]
     *   is established on the current thread. This indicates the
     *   service was reached outside the HTTP-request path without
     *   a manual context establishment — a bug the caller must
     *   fix, NOT a user-facing auth failure.
     */
    fun applyFromRequest() {
        val context = phiRequestContextHolder.get()
            ?: throw PhiContextMissingException(
                "PhiSessionContext.applyFromRequest() called without an " +
                    "established PhiRequestContext on the current thread. " +
                    "Every clinical @Transactional method must run inside a " +
                    "request where PhiRequestContextFilter has populated the " +
                    "holder, or must manually establish context (background " +
                    "jobs / async dispatch). Loud failure is deliberate " +
                    "(ADR-007 / 4A.0 refinement): silent no-op would risk a " +
                    "PHI-access path that bypasses RLS.",
            )
        // Both fields non-null by construction of PhiRequestContext;
        // partial-context rejection is structural, not runtime.
        tenancySessionContext.apply(
            userId = context.userId,
            tenantId = context.tenantId,
        )
    }
}
