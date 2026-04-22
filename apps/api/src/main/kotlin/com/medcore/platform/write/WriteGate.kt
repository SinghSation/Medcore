package com.medcore.platform.write

import org.slf4j.LoggerFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The single mutation contract for the Medcore EHR (ADR-007).
 *
 * Every state change passes through a [WriteGate] instance. The gate
 * enforces this six-step pipeline in exactly this order, with NO
 * bypass:
 *
 *   1. **validate** — optional [WriteValidator] runs against the
 *      canonical command. Throws → 422 (Phase 3G envelope).
 *   2. **authorize** — [AuthzPolicy.check] runs. Throws
 *      [WriteAuthorizationException] → the denial branch emits an
 *      `AUTHZ_WRITE_DENIED` audit event and re-throws. The throw
 *      propagates to Phase 3G's handler, which returns 403 with
 *      the uniform "Access denied." message.
 *   3. **transact-open** — the gate opens its OWN transaction via
 *      [TransactionTemplate]. Callers do NOT need (and MUST NOT
 *      rely on) `@Transactional` on enclosing service methods for
 *      WriteGate correctness. This guarantee is the entire point
 *      of owning the boundary here.
 *   4. **apply** — caller's `execute` lambda runs the state
 *      transition. It sees a live transaction. Anything it throws
 *      rolls back.
 *   5. **audit-success** — [WriteAuditor.onSuccess] emits the
 *      success audit event IN THE SAME TRANSACTION as the apply.
 *      ADR-003 §2 atomicity: if audit fails, the apply rolls back.
 *   6. **transact-close** — the template commits. Only after
 *      commit does `apply()` return the result to the caller.
 *
 * The denial path does NOT open a transaction. The denial audit
 * uses `JdbcAuditWriter`'s existing `PROPAGATION_REQUIRED`
 * semantics — it starts its own transaction because none exists.
 *
 * WriteGate is NOT a `@Component`. Each mutation module constructs
 * one gate per command type at configuration time, wiring in the
 * right policy + validator + auditor. That explicit construction
 * is traceable in a code review and kept out of AOP / magic
 * mechanisms.
 */
class WriteGate<CMD, R>(
    private val policy: AuthzPolicy<CMD>,
    private val auditor: WriteAuditor<CMD, R>,
    private val txManager: PlatformTransactionManager,
    private val validator: WriteValidator<CMD>? = null,
) {

    private val log = LoggerFactory.getLogger(WriteGate::class.java)
    private val txTemplate: TransactionTemplate = TransactionTemplate(txManager)

    /**
     * Runs the mutation. See class KDoc for the six-step
     * pipeline.
     */
    fun apply(command: CMD, context: WriteContext, execute: (CMD) -> R): R {
        // Step 1 — Validation (outside tx; purely command-shape).
        validator?.validate(command)

        // Step 2 — Authorization (outside tx; may issue DB reads
        // via its own short-lived connection; implementations are
        // expected to be efficient).
        try {
            policy.check(command, context)
        } catch (ex: WriteAuthorizationException) {
            // Step 2a — Denial audit in its own transaction (via
            // JdbcAuditWriter's PROPAGATION_REQUIRED). Re-throw
            // after audit writes so the caller's exception handler
            // translates to 403.
            try {
                auditor.onDenied(command, context, ex.reason)
            } catch (auditFailure: Throwable) {
                // A failed denial audit is a compliance incident —
                // log at ERROR but do NOT swallow the original
                // denial. The caller still gets their 403; the
                // incident surfaces via log monitoring.
                log.error(
                    "WriteGate: denial audit emission failed for reason=${ex.reason.code}",
                    auditFailure,
                )
            }
            throw ex
        }

        // Steps 3–6 — Success path. Transaction-owned execute +
        // audit-success. If either throws, the template rolls the
        // transaction back atomically.
        return txTemplate.execute {
            val result = execute(command)
            auditor.onSuccess(command, result, context)
            result
        }!! // Template returns non-null because our body always returns.
    }
}
