package com.medcore.clinical.patient.write

import java.util.UUID

/**
 * Command for
 * `DELETE /api/v1/tenants/{slug}/patients/{patientId}/identifiers/{identifierId}`
 * (Phase 4A.3).
 *
 * Revocation is SOFT DELETE via `valid_to = NOW()` — the row
 * persists in `clinical.patient_identifier` for audit integrity;
 * only its lifecycle state changes. Consistent with V14's
 * `valid_from` / `valid_to` schema design (the columns exist
 * specifically for lifecycle tracking).
 *
 * Follows `clinical-write-pattern.md` §2.1 — immutable data
 * class; no body, just path variables lifted into command
 * fields.
 *
 * ### Idempotency
 *
 * DELETE on an already-revoked identifier (valid_to already
 * set) is a no-op:
 *   - handler returns `changed = false`
 *   - auditor emits nothing (§6.4 no-op suppression)
 *   - HTTP response: 204
 *
 * This matches 3J.N's revoke-membership precedent. The DELETE
 * verb is idempotent.
 *
 * ### Carry-forward (UNIQUE constraint)
 *
 * Revocation does NOT free up the `(patient_id, type, issuer,
 * value)` UNIQUE index slot. The revoked row's tuple still
 * counts. A caller cannot add an identifier with the same
 * `(type, issuer, value)` after revoking it. Tracked as a
 * 4A.3 carry-forward — amend to a partial unique index
 * `WHERE valid_to IS NULL` if a pilot workflow demands
 * re-add-after-revoke.
 */
data class RevokePatientIdentifierCommand(
    val slug: String,
    val patientId: UUID,
    val identifierId: UUID,
)
