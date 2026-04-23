package com.medcore.clinical.patient.write

import com.medcore.clinical.patient.persistence.PatientIdentifierEntity
import com.medcore.clinical.patient.persistence.PatientIdentifierRepository
import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * Handler for [AddPatientIdentifierCommand] (Phase 4A.3).
 *
 * Follows `clinical-write-pattern.md` §5:
 * `[REQUIRED §5.1]` `@Component`, NOT `@Transactional`; runs inside
 * the `WriteGate`-owned transaction with RLS GUCs set by
 * `PhiRlsTxHook`.
 * `[REQUIRED §5.1]` execution order: load tenant → load target row
 * (here: the parent patient) → execute mutation → return snapshot.
 * `[REQUIRED §5.3]` cross-tenant id-probe → 404 (not 403).
 *
 * ### Flow
 *
 * 1. Load tenant by slug. Policy already resolved authority — the
 *    load is an invariant check (policy would have refused before
 *    we got here if tenant-slug doesn't map).
 * 2. Load the parent patient by id. 404 if missing OR if patient's
 *    `tenant_id != tenant.id` (cross-tenant probe — identical
 *    response to "patient does not exist").
 * 3. Build the [PatientIdentifierEntity] and `saveAndFlush`. DB-
 *    level `UNIQUE (patient_id, type, issuer, value)` enforces
 *    exact-duplicate refusal → `DataIntegrityViolationException`
 *    → 409 via 3G `onDataIntegrityViolation`.
 * 4. V17 RLS WITH CHECK validates the caller's OWNER/ADMIN role
 *    at the DB layer (defense in depth over [AddPatientIdentifierPolicy]).
 *
 * ### Carry-forward — re-add-after-revoke
 *
 * Soft-deleted identifiers (with `valid_to != NULL`) still count
 * toward the UNIQUE constraint. A caller cannot re-add an
 * identifier with the same `(type, issuer, value)` after revoking
 * it via [RevokePatientIdentifierHandler]. If a pilot workflow
 * demands re-add-after-revoke, amend the UNIQUE index to
 * `WHERE valid_to IS NULL` in a future migration. Tracked as
 * a 4A.3 carry-forward.
 *
 * ### No duplicate-warning path
 *
 * Unlike 4A.2's patient create (which uses
 * `DuplicatePatientDetector` for phonetic-match warnings),
 * identifiers have no fuzzy-match semantic — a driver's license
 * number either matches or doesn't. The UNIQUE constraint is the
 * correct gate. No additional `X-Confirm-Duplicate` pattern
 * needed here.
 */
@Component
class AddPatientIdentifierHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
    private val patientIdentifierRepository: PatientIdentifierRepository,
    private val clock: Clock,
) {

    fun handle(
        command: AddPatientIdentifierCommand,
        @Suppress("UNUSED_PARAMETER") context: WriteContext,
    ): PatientIdentifierSnapshot {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val patient = patientRepository.findById(command.patientId).orElse(null)
            ?: throw EntityNotFoundException("patient not found: ${command.patientId}")
        if (patient.tenantId != tenant.id) {
            // Cross-tenant probe — identical response to "patient
            // does not exist" so existence does not leak.
            throw EntityNotFoundException("patient not found: ${command.patientId}")
        }

        val now = Instant.now(clock)
        val id = UUID.randomUUID()
        val entity = PatientIdentifierEntity(
            id = id,
            patientId = patient.id,
            type = command.type,
            issuer = command.issuer,
            value = command.value,
            validFrom = command.validFrom,
            validTo = command.validTo,
            createdAt = now,
            updatedAt = now,
        )
        val saved = patientIdentifierRepository.saveAndFlush(entity)

        return PatientIdentifierSnapshot(
            id = saved.id,
            patientId = saved.patientId,
            tenantId = tenant.id,
            type = saved.type,
            issuer = saved.issuer,
            value = saved.value,
            validFrom = saved.validFrom,
            validTo = saved.validTo,
            createdAt = saved.createdAt,
            updatedAt = saved.updatedAt,
            rowVersion = saved.rowVersion,
            changed = true,
        )
    }
}
