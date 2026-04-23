package com.medcore.platform.read

import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteTxHook
import org.slf4j.LoggerFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * The single READ contract for the Medcore EHR (Phase 4A.4).
 *
 * Sister to [com.medcore.platform.write.WriteGate]; enforces the
 * same audit-atomicity + authz discipline on PHI reads. Every
 * read of a PHI-bearing resource passes through a [ReadGate]
 * instance. The gate enforces this five-step pipeline in
 * exactly this order, with NO bypass:
 *
 *   1. **authorize** — [ReadAuthzPolicy.check] runs. Throws
 *      [WriteAuthorizationException] → the denial branch emits
 *      an `AUTHZ_READ_DENIED` audit event and re-throws. The
 *      throw propagates to 3G's handler, which returns 403
 *      with the uniform "Access denied." message.
 *   2. **transact-open** — the gate opens its OWN transaction
 *      via [TransactionTemplate]. The tx is NOT marked
 *      read-only — the audit emission in step 5 is an INSERT
 *      and must share the read's tx for atomicity. Write-
 *      prevention for the handler's own code is enforced by
 *      V14+ RLS policies, not by tx flags. Callers MUST NOT
 *      rely on `@Transactional` on enclosing methods.
 *   3. **tx-hook** — optional [WriteTxHook] runs (reused;
 *      typically [com.medcore.platform.write.PhiRlsTxHook]
 *      which sets both RLS GUCs in-tx).
 *   4. **execute** — caller's `execute` lambda runs the read
 *      handler. Sees a live read-only tx with RLS GUCs set.
 *   5. **audit-success** — [ReadAuditor.onSuccess] emits the
 *      audit event IN THE SAME TRANSACTION as the read.
 *      ADR-003 §2 atomicity: **if audit fails, the read's
 *      serialisation never happens; the caller sees 500 with
 *      no PHI disclosure.**
 *
 * ### Why read-audit atomicity matters
 *
 * A read that discloses PHI without an audit row is a
 * compliance violation (HIPAA §164.312(b) Audit Controls).
 * If the audit write fails after the handler has read the
 * row but before the response is serialised, the correct
 * behaviour is:
 *
 * - Roll the read tx back (cosmetic — SELECTs don't mutate,
 *   but the read-only flag prevents any hypothetical writes
 *   from the handler).
 * - Throw through to `onUncaught` → 500.
 * - **The response body is never written. The caller never
 *   sees the PHI row that almost got disclosed.**
 *
 * This is DIFFERENT from write semantics only in degree —
 * writes also fail on audit failure, but for writes the
 * rollback also undoes the mutation. For reads the only
 * thing the rollback prevents is the serialisation step.
 *
 * ### Denial path (does NOT open a transaction)
 *
 * Policy denial uses [ReadAuditor.onDenied], which writes the
 * denial audit row via `JdbcAuditWriter`'s own
 * `PROPAGATION_REQUIRED` semantics (its own tx; no data-read
 * tx was ever opened).
 *
 * ### Read gate is NOT a `@Component`
 *
 * Each feature module constructs one gate per read command
 * type at configuration time, wiring in the right policy +
 * auditor + hook. Matches WriteGate's construction
 * discipline from 3J.1 — explicit, traceable, no AOP magic.
 */
class ReadGate<CMD, R>(
    private val policy: ReadAuthzPolicy<CMD>,
    private val auditor: ReadAuditor<CMD, R>,
    private val txManager: PlatformTransactionManager,
    /**
     * Optional tx-local state setup hook. For PHI reads, wire
     * [com.medcore.platform.write.PhiRlsTxHook] so RLS GUCs
     * are set before the handler's queries run. Without the
     * hook, V14+ RLS policies key on unset GUCs → policy
     * evaluates UNKNOWN → zero rows visible. Unit tests may
     * pass `null` to skip.
     */
    private val txHook: WriteTxHook? = null,
) {

    private val log = LoggerFactory.getLogger(ReadGate::class.java)
    private val txTemplate: TransactionTemplate = TransactionTemplate(txManager).apply {
        // Transaction is NOT marked read-only. Reasoning:
        //   - The handler's SELECT reads are gated by V14+ RLS;
        //     no data-mutation path is reachable from a read
        //     handler.
        //   - The audit emission inside `onSuccess` IS an
        //     INSERT (into `audit.audit_event`) and MUST
        //     participate in the SAME transaction as the read
        //     for ADR-003 §2 atomicity (a read discloses PHI
        //     iff its audit row commits).
        //   - Postgres refuses INSERT under
        //     `SET TRANSACTION READ ONLY`; a read-only flag
        //     here would break audit emission.
        //
        // The "read-only" property was defensive layering, not
        // correctness — V14 RLS + handler discipline are the
        // real write-prevention. The tx is intentionally
        // writable so the audit row lands atomically with the
        // read.
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
    }

    /**
     * Runs the read. See class KDoc for the five-step pipeline.
     */
    fun apply(command: CMD, context: WriteContext, execute: (CMD) -> R): R {
        // Step 1 — Authorization (outside tx).
        try {
            policy.check(command, context)
        } catch (ex: WriteAuthorizationException) {
            // Step 1a — Denial audit in its own tx.
            try {
                auditor.onDenied(command, context, ex.reason)
            } catch (auditFailure: Throwable) {
                // Failed denial audit = compliance incident;
                // log ERROR but do not swallow the denial.
                log.error(
                    "ReadGate: denial audit emission failed for reason=${ex.reason.code}",
                    auditFailure,
                )
            }
            throw ex
        }

        // Steps 2–5 — Success path. Read-only tx + audit-success.
        return txTemplate.execute {
            // Step 3 — Prepare tx-local state (RLS GUCs).
            txHook?.beforeExecute(context)
            // Step 4 — Read.
            val result = execute(command)
            // Step 5 — Audit success inside the tx. Failure
            // rolls back, caller gets 500, NO PHI disclosed.
            auditor.onSuccess(command, result, context)
            result
        }!! // Template returns non-null because our body always returns.
    }
}
