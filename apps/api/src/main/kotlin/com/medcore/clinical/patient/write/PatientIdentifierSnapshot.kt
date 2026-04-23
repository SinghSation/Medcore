package com.medcore.clinical.patient.write

import com.medcore.clinical.patient.model.PatientIdentifierType
import java.time.Instant
import java.util.UUID

/**
 * Snapshot returned by identifier-write handlers to controllers
 * (Phase 4A.3). Follows `clinical-write-pattern.md` §5.6:
 * `[REQUIRED]` plain data — no JPA references, no lazy loading.
 *
 * ### PHI discipline
 *
 * Same rules as [PatientSnapshot]: this type carries `value` which
 * is PHI for DRIVERS_LICENSE / INSURANCE_MEMBER. Never emitted to
 * logs, MDC, spans, or error envelopes. Controllers build
 * `PatientIdentifierResponse` DTOs from this snapshot directly.
 *
 * ### `changed` field
 *
 * Required for the revoke path's no-op suppression (idempotent
 * DELETE-to-already-revoked returns `changed = false`, auditor
 * emits nothing). The add path always returns `changed = true`.
 */
data class PatientIdentifierSnapshot(
    val id: UUID,
    val patientId: UUID,
    val tenantId: UUID,

    val type: PatientIdentifierType,
    val issuer: String,
    val value: String,

    val validFrom: Instant?,
    val validTo: Instant?,

    val createdAt: Instant,
    val updatedAt: Instant,
    val rowVersion: Long,

    /**
     * `true` if the handler persisted a change, `false` for
     * no-op (idempotent revoke-on-already-revoked). Controllers
     * do not act on this; auditors suppress emission when false.
     */
    val changed: Boolean,
)
