package com.medcore.platform.write

/**
 * Emits audit events for a specific command/result pair. Implementations
 * live in the command's module and decide:
 *
 * - The coarse [com.medcore.platform.audit.AuditAction] (e.g.,
 *   `TENANCY_TENANT_UPDATED`).
 * - The fine-grain intent slug emitted via the audit event's
 *   `reason` field (e.g., `intent:tenant.update_display_name`).
 *   Per ADR-007 §4.4, every mutation MUST carry an intent so
 *   auditors can distinguish sibling mutations sharing a coarse
 *   action code.
 * - The resource type/id, tenant id, and other closed-set fields
 *   the audit event requires.
 *
 * Both [onSuccess] and [onDenied] emit via the existing
 * [com.medcore.platform.audit.AuditWriter] — same transaction
 * semantics, same append-only path, same chain.
 */
interface WriteAuditor<CMD, R> {
    /**
     * Called inside the write transaction after the command
     * successfully applies. Throwing here propagates and rolls back
     * the mutation (ADR-003 §2: audit failure fails the audited
     * action).
     */
    fun onSuccess(command: CMD, result: R, context: WriteContext)

    /**
     * Called OUTSIDE the write transaction (the write never opened)
     * when authorization refuses the command. Uses the standard
     * `AUTHZ_WRITE_DENIED` action with the denial reason code in
     * the `reason` field.
     */
    fun onDenied(command: CMD, context: WriteContext, reason: WriteDenialReason)
}
