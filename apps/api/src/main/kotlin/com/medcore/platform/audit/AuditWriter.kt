package com.medcore.platform.audit

/**
 * Synchronous, append-only audit writer (ADR-003 §2, Rule 06).
 *
 * Contract:
 *   - Persists exactly one `audit.audit_event` row per call, in the
 *     caller's transactional boundary when one exists, in a fresh
 *     transaction otherwise.
 *   - MUST NOT swallow persistence errors. ADR-003 §2: "A failure to
 *     write audit fails the audited action." Callers inside a
 *     `@Transactional` scope therefore roll back; callers outside
 *     (filter, authentication entry point) surface a 500 rather than
 *     silently proceeding.
 *   - Fire-and-forget, async, or log-only implementations are
 *     prohibited (Rule 06).
 */
interface AuditWriter {
    fun write(command: AuditEventCommand)
}
