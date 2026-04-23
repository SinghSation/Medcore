package com.medcore.clinical.patient.write

import com.medcore.clinical.patient.persistence.PatientIdentifierRepository
import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import java.time.Instant
import org.springframework.stereotype.Component

/**
 * Handler for [RevokePatientIdentifierCommand] (Phase 4A.3).
 *
 * Follows `clinical-write-pattern.md` §5. Executes inside the
 * WriteGate-owned transaction with RLS GUCs set by `PhiRlsTxHook`.
 *
 * ### Flow
 *
 * 1. Load tenant by slug.
 * 2. Load parent patient; 404 if not found OR cross-tenant (§5.3).
 * 3. Load identifier by id. 404 if not found OR if its
 *    `patient_id` doesn't match the patient from the URL path.
 *    (Defense against ID-smuggling: caller passes a real
 *    identifier ID owned by a different patient — the route
 *    scoping via URL path variables pins patient_id; any
 *    mismatch → 404.)
 * 4. **Idempotency check:** if the identifier's `valid_to` is
 *    already set (already revoked), return `changed = false`.
 *    Handler returns the snapshot unmodified; auditor suppresses
 *    emission; controller returns 204 with no side effect.
 * 5. Otherwise set `valid_to = NOW()` and `updated_at = NOW()`;
 *    `saveAndFlush` — `@Version` bumps `row_version`.
 * 6. V17 RLS WITH CHECK validates the caller's OWNER/ADMIN role
 *    at the DB layer (defense in depth over
 *    [RevokePatientIdentifierPolicy]).
 *
 * ### No If-Match (clinical-write-pattern §7.2 + plan §4.3)
 *
 * Revoke is a lifecycle transition, not a demographic edit. The
 * idempotent nature (same result regardless of how many times
 * DELETE is sent) + soft-delete semantics mean the stale-version
 * concern `If-Match` addresses on demographic PATCH does not
 * apply here. Matches 3J.N's `DELETE /memberships/{id}`
 * precedent.
 *
 * ### No `updated_by` column (plan §4.3 sub-decision)
 *
 * Unlike [com.medcore.clinical.patient.persistence.PatientEntity],
 * [com.medcore.clinical.patient.persistence.PatientIdentifierEntity]
 * does not carry `updated_by`. The audit row is the system of
 * record for "who revoked when." Adding the column would be
 * redundant with the audit trail.
 */
@Component
class RevokePatientIdentifierHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
    private val patientIdentifierRepository: PatientIdentifierRepository,
    private val clock: Clock,
) {

    fun handle(
        command: RevokePatientIdentifierCommand,
        @Suppress("UNUSED_PARAMETER") context: WriteContext,
    ): PatientIdentifierSnapshot {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val patient = patientRepository.findById(command.patientId).orElse(null)
            ?: throw EntityNotFoundException("patient not found: ${command.patientId}")
        if (patient.tenantId != tenant.id) {
            throw EntityNotFoundException("patient not found: ${command.patientId}")
        }

        val identifier = patientIdentifierRepository.findById(command.identifierId).orElse(null)
            ?: throw EntityNotFoundException("identifier not found: ${command.identifierId}")
        if (identifier.patientId != patient.id) {
            // Identifier belongs to a different patient (cross-
            // patient id probe). Identical response to "identifier
            // does not exist" — no existence leak.
            throw EntityNotFoundException("identifier not found: ${command.identifierId}")
        }

        if (identifier.validTo != null) {
            // Already revoked — idempotent no-op. Return snapshot
            // with `changed = false`; auditor suppresses emission.
            return toSnapshot(identifier, tenant.id, changed = false)
        }

        val now = Instant.now(clock)
        identifier.validTo = now
        identifier.updatedAt = now
        val saved = patientIdentifierRepository.saveAndFlush(identifier)

        return toSnapshot(saved, tenant.id, changed = true)
    }

    private fun toSnapshot(
        entity: com.medcore.clinical.patient.persistence.PatientIdentifierEntity,
        tenantId: java.util.UUID,
        changed: Boolean,
    ): PatientIdentifierSnapshot = PatientIdentifierSnapshot(
        id = entity.id,
        patientId = entity.patientId,
        tenantId = tenantId,
        type = entity.type,
        issuer = entity.issuer,
        value = entity.value,
        validFrom = entity.validFrom,
        validTo = entity.validTo,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        rowVersion = entity.rowVersion,
        changed = changed,
    )
}
