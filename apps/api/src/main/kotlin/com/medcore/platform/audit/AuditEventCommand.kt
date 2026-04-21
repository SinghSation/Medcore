package com.medcore.platform.audit

import java.util.UUID

/**
 * Typed input to [AuditWriter]. Intentionally flat, typed, and free of
 * free-form maps / JSON bags (Rule 01, Rule 06; ADR-003 §3).
 *
 * PHI discipline:
 *   - [actorDisplay], [reason], [resourceType], [resourceId] accept short
 *     stable strings only (role names, coarse reason codes,
 *     already-opaque identifiers). They MUST NOT receive user-supplied
 *     text, email, display name, raw headers, exception messages, or any
 *     clinical content.
 *   - [clientIp], [userAgent], [requestId] are populated by the writer
 *     from [RequestMetadataProvider]; callers MUST NOT set them
 *     themselves.
 *
 * Any new persisted field requires an additive migration AND an update
 * to the PHI-exposure review for the slice that introduces it.
 */
data class AuditEventCommand(
    val action: AuditAction,
    val actorType: ActorType,
    val actorId: UUID?,
    val actorDisplay: String? = null,
    val tenantId: UUID? = null,
    val resourceType: String? = null,
    val resourceId: String? = null,
    val outcome: AuditOutcome,
    val reason: String? = null,
)
