package com.medcore.platform.read

import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason

/**
 * Emits audit events for read operations (Phase 4A.4).
 *
 * Sister type to
 * [com.medcore.platform.write.WriteAuditor] with the same
 * shape — distinct interface retained for semantic clarity
 * (read-path vs write-path auditors are conceptually
 * different even when their signature is identical).
 *
 * ### Emission discipline (NORMATIVE)
 *
 * [onSuccess] emits ONLY on actual data disclosure (200 OK
 * with body returned). Implementations MUST NOT emit when:
 *
 * - The resource was not found (404) — no disclosure occurred;
 *   emitting would flood the chain with routine misses.
 * - The server errored (500) — application bug, not a
 *   compliance event.
 *
 * [onDenied] emits on policy-level denials (403 from
 * [ReadAuthzPolicy]) with `AUTHZ_READ_DENIED`. Filter-level
 * denials (SUSPENDED membership, not-a-member) emit
 * `tenancy.membership.denied` via `TenantContextFilter` and
 * never reach the gate — not this auditor's concern.
 *
 * ### Atomicity (ADR-003 §2)
 *
 * [onSuccess] runs INSIDE the gate's read-only transaction.
 * If the audit write fails, the transaction rolls back, the
 * read response body is NEVER serialised, and the caller
 * receives a 500 via 3G's `onUncaught`. **A read that could
 * not be audited MUST NOT disclose PHI.**
 *
 * Per-command implementations:
 *   - reside in `..read..` packages adjacent to their command
 *   - are `@Component`-scoped for Spring DI
 *   - emit exactly ONE audit row per success; ZERO on 404/500;
 *     ONE on policy denial
 */
interface ReadAuditor<CMD, R> {
    /**
     * Called inside the read tx after the handler returns
     * successfully. Implementations MUST write an audit row;
     * failure propagates, rolls the tx back, and surfaces as
     * 500 to the caller (no PHI disclosed).
     */
    fun onSuccess(command: CMD, result: R, context: WriteContext)

    /**
     * Called OUTSIDE the gate's transaction (which never
     * opened) when [ReadAuthzPolicy.check] refuses the read.
     * Emits `AUTHZ_READ_DENIED` with the
     * [com.medcore.platform.audit.AuditAction] + the denial
     * reason slug.
     */
    fun onDenied(command: CMD, context: WriteContext, reason: WriteDenialReason)
}
