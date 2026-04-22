package com.medcore.platform.write

import com.medcore.platform.persistence.TenancySessionContext
import org.springframework.stereotype.Component

/**
 * [WriteTxHook] that sets the V8 RLS GUC `app.current_user_id`
 * inside the gate's transaction (Phase 3J.2).
 *
 * Without this hook, WriteGate's transaction has NO `app.current_user_id`
 * set — the policy check sets it only in its own short-lived
 * read-only transaction, which commits and discards the
 * `SET LOCAL`-scoped value before the gate's tx opens. The
 * handler then reads zero rows from any RLS-protected table and
 * mutations appear to "fail with 404" even when authorization
 * passed.
 *
 * Every Medcore mutation that touches an RLS-protected table (i.e.,
 * every tenancy write; in Phase 4+ every PHI-bearing write) wires
 * this hook into its [WriteGate] construction (see
 * `TenantWriteConfig`).
 *
 * ### Why this is platform/write/, not tenancy/
 *
 * RLS is a cross-cutting platform concern — every module that
 * mutates tenant-scoped or patient-scoped data needs the same
 * GUC set. Lifting the hook to the write-framework layer avoids
 * each module duplicating the same four lines. If a future
 * module has mutation needs that don't involve RLS (e.g., a
 * reference-data loader that runs as `medcore_migrator` via
 * SECURITY DEFINER), it constructs its WriteGate without this
 * hook.
 */
@Component
class TenancyRlsTxHook(
    private val sessionContext: TenancySessionContext,
) : WriteTxHook {

    override fun beforeExecute(context: WriteContext) {
        // Tenant-id is deliberately null here. V8's tenancy policies
        // key only on `app.current_user_id`; `app.current_tenant_id`
        // is reserved for Phase 4+ PHI tables and will be wired by
        // a dedicated hook at that time (the PHI hook needs the
        // command's tenant-id, which is domain-specific).
        sessionContext.apply(userId = context.principal.userId, tenantId = null)
    }
}
