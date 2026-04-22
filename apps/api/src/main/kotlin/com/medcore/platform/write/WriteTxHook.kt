package com.medcore.platform.write

/**
 * Callback invoked by [WriteGate] at the very start of the gate's
 * transaction block — after `transact-open`, before the handler's
 * `apply` step (Phase 3J.2).
 *
 * ### Why this exists
 *
 * The V8 RLS policies on `tenancy.tenant` / `tenancy.tenant_membership`
 * read the Postgres GUC `app.current_user_id`. That GUC is set via
 * `set_config(..., is_local := true)` — **transaction-scoped**. If
 * the policy check (which runs OUTSIDE WriteGate's transaction in
 * its own read-only tx) sets the GUC, it is discarded when that
 * tx commits. The handler then runs in a fresh tx with no GUC set,
 * and RLS filters every row to zero.
 *
 * [WriteTxHook] is the single, explicit seam for restoring caller-
 * dependent transaction state inside the gate's tx. It receives
 * the live [WriteContext] so implementations can read `principal`
 * and `idempotencyKey` without global state.
 *
 * ### Contract
 *
 * - MUST throw only if the transaction should be aborted. Any
 *   exception propagates to [WriteGate.apply]'s caller unchanged
 *   — the transaction rolls back, `onSuccess` is NOT invoked.
 * - MUST NOT emit audit events. Audit emission lives in
 *   [WriteAuditor.onSuccess] / `onDenied`; the hook's job is
 *   state preparation only.
 * - MUST NOT perform network I/O or call external services.
 *   Runs synchronously on the request thread and delays the
 *   handler.
 *
 * ### Tests
 *
 * Unit-level [WriteGate] tests pass `txHook = null` to remain pure
 * in-memory. Integration-level tenancy tests wire
 * `TenancyRlsTxHook` (the production implementation) so RLS is
 * exercised end-to-end.
 */
fun interface WriteTxHook {
    fun beforeExecute(context: WriteContext)
}
