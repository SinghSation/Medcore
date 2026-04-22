package com.medcore.platform.audit.chain

import java.sql.SQLException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * Read-only inspector for the audit chain (V9, Phase 3D). Invokes
 * `audit.verify_chain()` and translates the result into a typed
 * [ChainVerificationResult] the scheduler interprets for logging and
 * audit-emission decisions.
 *
 * **Side-effect-free by design** — this class intentionally does no
 * logging. Failures are returned via
 * [ChainVerificationResult.VerifierFailed] carrying the cause; the
 * scheduler is the single source for both log emission and audit
 * emission. This avoids the double-logging anti-pattern where a
 * single infra failure produces two ERROR lines (one from the
 * verifier, one from the scheduler).
 *
 * Read-only guarantee:
 *   - Uses `JdbcTemplate.query` with a SELECT-only invocation of the
 *     verify function. No INSERT, no UPDATE, no DELETE.
 *   - Never calls `audit.rebuild_chain()` — that function's EXECUTE
 *     grant is restricted to migrator/superuser anyway; the verifier
 *     code does not reference its name.
 *   - [ChainVerifierReadOnlyTest] asserts that a verification round
 *     leaves every row in `audit.audit_event` unchanged (count,
 *     sequence numbers, and row-hashes all identical before and
 *     after).
 *
 * Failure isolation:
 *   - A database-level failure (connection refused, function missing,
 *     permission denied) is translated to
 *     [ChainVerificationResult.VerifierFailed]. The verifier does
 *     NOT re-throw — a failed verification is a signal, not an
 *     application-killing event. The scheduler logs ERROR with the
 *     carried cause + emits a dedicated
 *     `audit.chain.verification_failed` audit event per cycle,
 *     distinct from chain-integrity failures.
 */
@Service
open class ChainVerifier(private val jdbcTemplate: JdbcTemplate) {

    /**
     * Open for a narrow test-only override in
     * `ChainVerificationSchedulerTest.ProgrammableVerifier`, which
     * substitutes an in-memory outcome to exercise scheduler
     * dispatch paths without driving real DB tampering. Production
     * code MUST NOT subclass this or replace the default
     * implementation; the `@TestConfiguration` in the test file is
     * the only permitted alternate.
     */
    open fun verify(): ChainVerificationResult =
        try {
            val breaks: List<String> = jdbcTemplate.query(
                "SELECT reason FROM audit.verify_chain()",
            ) { rs, _ -> rs.getString("reason") }

            if (breaks.isEmpty()) {
                ChainVerificationResult.Clean
            } else {
                ChainVerificationResult.Broken(
                    breakCount = breaks.size,
                    firstReason = breaks.first(),
                )
            }
        } catch (ex: SQLException) {
            ChainVerificationResult.VerifierFailed(ex)
        } catch (ex: org.springframework.dao.DataAccessException) {
            // Spring wraps SQLException in DataAccessException variants —
            // catch those too so a JDBC-template-level wrap doesn't leak
            // into the scheduler as a generic Throwable.
            ChainVerificationResult.VerifierFailed(ex)
        }
}

/**
 * Exhaustive result type for a single verification round.
 */
sealed class ChainVerificationResult {

    /** Zero break rows returned by `audit.verify_chain()`. */
    data object Clean : ChainVerificationResult()

    /**
     * At least one break row returned. [firstReason] is one of the
     * closed codes emitted by `audit.verify_chain()` in V9:
     *   - `sequence_no_null`
     *   - `sequence_gap`
     *   - `first_row_has_prev_hash`
     *   - `prev_hash_mismatch`
     *   - `row_hash_mismatch`
     */
    data class Broken(val breakCount: Int, val firstReason: String) : ChainVerificationResult()

    /**
     * The verifier itself could not run (DB unreachable, function
     * missing, permission denied). Distinct from [Broken] so the
     * scheduler emits a different audit action for compliance
     * visibility. [cause] is carried through so the scheduler — the
     * single logging source — can log it at ERROR without the
     * verifier also emitting a log line.
     */
    data class VerifierFailed(val cause: Throwable) : ChainVerificationResult()
}
