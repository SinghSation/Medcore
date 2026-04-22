package com.medcore.platform.audit.chain

import com.medcore.platform.audit.ActorType
import com.medcore.platform.audit.AuditAction
import com.medcore.platform.audit.AuditEventCommand
import com.medcore.platform.audit.AuditOutcome
import com.medcore.platform.audit.AuditWriter
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Runs [ChainVerifier] on the configured cadence and translates the
 * outcome into logs + audit events (Phase 3F.4).
 *
 * # ⚠️ Single-instance only
 *
 * This scheduler is safe ONLY when Medcore runs as a single
 * instance. Deploying Medcore horizontally (multiple pods / tasks /
 * instances sharing one database) will produce **duplicate audit
 * events** — every instance's scheduler will fire on the same cron
 * and each will observe the same chain state. A distributed lock
 * (ShedLock against the shared Postgres, or equivalent) MUST land
 * before Medcore runs multi-instance. Tracked as carry-forward to
 * the slice that introduces horizontal scaling.
 *
 * The Phase 3I deployment baseline (ECS Fargate) is single-instance
 * at first; this constraint will be lifted by ADR when scale demands
 * it.
 *
 * Behaviour matrix:
 *
 * | Outcome              | Log level             | Audit event                         |
 * |----------------------|-----------------------|-------------------------------------|
 * | Clean                | DEBUG (prod default), | none                                |
 * |                      | INFO (dev opt-in via  |                                     |
 * |                      | verboseCleanLog=true) |                                     |
 * | Broken               | ERROR                 | `audit.chain.integrity_failed`      |
 * |                      |                       | (one per cycle; reason format:      |
 * |                      |                       | `breaks:<N>\|reason:<first-code>`)  |
 * | VerifierFailed       | ERROR (raised inside  | `audit.chain.verification_failed`   |
 * |                      | [ChainVerifier])      | (one per cycle; reason slug         |
 * |                      |                       | `verifier_failed` with no detail)   |
 *
 * Concurrency guard:
 *   An [AtomicBoolean] prevents overlapping executions when a verify
 *   run takes longer than the cron interval. If the guard is already
 *   held, the current tick is skipped (logged at DEBUG, no audit).
 *   Scope: single-instance. When Medcore runs multi-instance, a
 *   distributed lock (ShedLock or equivalent) replaces this via a
 *   dedicated ADR.
 *
 * Failure isolation:
 *   The outer [Throwable] catch prevents a bug in the scheduler
 *   (e.g., audit-writer outage mid-emission) from killing the
 *   scheduled-task thread. The scheduler always resets the
 *   concurrency guard and schedules the next tick. This differs
 *   deliberately from ADR-003 §2's rule that audit-write failure
 *   fails the audited action — verification is a background
 *   observability task, not part of a user-facing transaction, so
 *   shielding it from bubbling a failure is the right trade-off.
 *
 * Conditional activation:
 *   `@ConditionalOnProperty` with `matchIfMissing = true` means the
 *   scheduler is active by default. Disable per-env via
 *   `MEDCORE_AUDIT_CHAIN_VERIFICATION_ENABLED=false`. Test
 *   environments override the enabled default in
 *   application-test.yaml.
 */
@Component
@ConditionalOnProperty(
    value = ["medcore.audit.chain-verification.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ChainVerificationScheduler(
    private val verifier: ChainVerifier,
    private val auditWriter: AuditWriter,
    private val properties: ChainVerificationProperties,
) {

    private val log = LoggerFactory.getLogger(ChainVerificationScheduler::class.java)
    private val running = AtomicBoolean(false)

    /**
     * Cron expression bound via property placeholder (resolved at
     * context-startup). `zone = "UTC"` pins the evaluation to a
     * stable timezone. First execution fires at the next matching
     * cron time after application start — Spring does not allow
     * a separate initial-delay for cron triggers. For fast-feedback
     * dev iteration, shorten the cron via env var (e.g.,
     * `MEDCORE_AUDIT_CHAIN_VERIFICATION_CRON="*\/30 * * * * *"`).
     */
    @Scheduled(
        cron = "\${medcore.audit.chain-verification.cron}",
        zone = "UTC",
    )
    fun scheduledVerification() {
        runVerification()
    }

    /**
     * Direct entry-point for tests — bypasses the cron trigger.
     * Callers MUST NOT use this in production code; the `@Scheduled`
     * annotation above is the only production trigger.
     */
    fun runVerification() {
        if (!running.compareAndSet(false, true)) {
            log.debug("chain verification already in progress; skipping this tick")
            return
        }
        try {
            dispatchResult(verifier.verify())
        } catch (ex: Throwable) {
            // Belt on top of [ChainVerifier]'s braces: any Throwable
            // that reaches here (e.g., audit-writer outage during
            // emission) is logged and swallowed so the next tick can
            // still run.
            log.error("chain verification scheduler failed unexpectedly", ex)
        } finally {
            running.set(false)
        }
    }

    private fun dispatchResult(result: ChainVerificationResult) {
        when (result) {
            ChainVerificationResult.Clean -> onClean()
            is ChainVerificationResult.Broken -> onBroken(result)
            is ChainVerificationResult.VerifierFailed -> onVerifierFailed(result)
        }
    }

    private fun onClean() {
        val message = "audit chain verified: no breaks"
        if (properties.verboseCleanLog) {
            log.info(message)
        } else {
            log.debug(message)
        }
    }

    private fun onBroken(result: ChainVerificationResult.Broken) {
        val reason = "breaks:${result.breakCount}|reason:${result.firstReason}"
        log.error(
            "AUDIT CHAIN INTEGRITY FAILURE: {} break(s) detected, first reason={}",
            result.breakCount,
            result.firstReason,
        )
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.AUDIT_CHAIN_INTEGRITY_FAILED,
                actorType = ActorType.SYSTEM,
                actorId = null,
                tenantId = null,
                outcome = AuditOutcome.ERROR,
                reason = reason,
            ),
        )
    }

    private fun onVerifierFailed(result: ChainVerificationResult.VerifierFailed) {
        // Scheduler is the single logging source for verifier
        // failures. The verifier itself is side-effect-free by
        // design; it returned the cause via the result type rather
        // than logging independently. Emit one ERROR log with the
        // carried cause, then one audit event so compliance
        // reviewers can distinguish "chain broken" from "could not
        // check chain."
        log.error("audit chain verification could not run", result.cause)
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.AUDIT_CHAIN_VERIFICATION_FAILED,
                actorType = ActorType.SYSTEM,
                actorId = null,
                tenantId = null,
                outcome = AuditOutcome.ERROR,
                reason = "verifier_failed",
            ),
        )
    }
}
