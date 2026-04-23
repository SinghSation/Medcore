package com.medcore.platform.write

import com.medcore.platform.security.phi.PhiSessionContext
import org.springframework.stereotype.Component

/**
 * [WriteTxHook] for PHI-bearing write gates (Phase 4A.0+).
 *
 * Sets the full RLS GUC pair (`app.current_user_id` +
 * `app.current_tenant_id`) inside the gate's transaction by
 * delegating to [PhiSessionContext.applyFromRequest].
 *
 * ### Relationship to [TenancyRlsTxHook]
 *
 * [TenancyRlsTxHook] (Phase 3J.2) sets only
 * `app.current_user_id`. Tenancy-scope RLS policies from V8 and
 * V12 need just that one GUC because they derive tenant scope
 * by joining `tenant_membership`.
 *
 * PHI tables introduced in Phase 4A (patient) and beyond
 * (encounter, note, order, etc.) have RLS policies that ALSO
 * key on `app.current_tenant_id` directly — multi-column index
 * hits, simpler policy expressions, and defensive framing in
 * case `tenant_membership` is ever cached or denormalized. The
 * PHI-aware hook is the right tool for every PHI write gate.
 *
 * ### Choosing between hooks in `TenantWriteConfig`
 *
 * - `updateTenantDisplayNameGate`, `inviteTenantMembershipGate`,
 *   `updateTenantMembershipRoleGate`, `revokeTenantMembershipGate`
 *   — tenancy writes; continue using [TenancyRlsTxHook].
 * - Future patient / encounter / note / order gates — use this
 *   hook.
 *
 * ### Failure semantics
 *
 * - If [PhiRequestContextHolder] is empty (no HTTP filter ran,
 *   no manual context establishment): throws
 *   [com.medcore.platform.security.phi.PhiContextMissingException]
 *   which propagates out of the gate's tx (tx rolls back) and is
 *   caught by 3G's fallback handler → 500. The caller sees
 *   "server error," the operator sees the full stack trace in
 *   structured logs. Same behaviour as a clinical service method
 *   forgetting `applyFromRequest()`.
 */
@Component
class PhiRlsTxHook(
    private val phiSessionContext: PhiSessionContext,
) : WriteTxHook {

    override fun beforeExecute(context: WriteContext) {
        // Context parameter is unused — PHI GUCs are resolved
        // from PhiRequestContextHolder, NOT from WriteContext.
        // Rationale: WriteContext carries principal + idempotency
        // key but NOT tenant id (by design — tenant is resolved
        // per-request from headers, not carried through command
        // signatures). PhiRequestContextHolder is the right
        // source of truth because it carries both halves of the
        // PHI context.
        phiSessionContext.applyFromRequest()
    }
}
