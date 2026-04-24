package com.medcore.clinical.encounter.write

import com.medcore.clinical.encounter.model.EncounterStatus
import com.medcore.clinical.encounter.persistence.EncounterEntity
import com.medcore.clinical.encounter.persistence.EncounterNoteRepository
import com.medcore.clinical.encounter.persistence.EncounterRepository
import com.medcore.platform.write.WriteConflictException
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import java.time.Instant
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Component

/**
 * Handler for [FinishEncounterCommand] (Phase 4C.5).
 *
 * Enforces the invariant the entire 4D.5 → 4C.5 sequencing was
 * built for: **an encounter can only be FINISHED if at least
 * one note on it is SIGNED.**
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug.
 * 2. Load encounter; verify `encounter.tenantId == tenant.id`.
 *    404 identity discipline.
 * 3. If `status != IN_PROGRESS` →
 *    [WriteConflictException]`("encounter_already_closed")`
 *    → 409 `resource.conflict` + `details.reason:
 *    encounter_already_closed`.
 * 4. Count signed notes via
 *    [EncounterNoteRepository.countSignedByEncounterId].
 *    If 0 → [WriteConflictException]`(
 *    "encounter_has_no_signed_notes")` → 409 with
 *    `details.reason: encounter_has_no_signed_notes`.
 * 5. Transition: `status = FINISHED`, `finished_at = now()`,
 *    `updated_at`, `updated_by`. `saveAndFlush`.
 * 6. On race (optimistic-lock failure at saveAndFlush), translate
 *    to [WriteConflictException]`("encounter_already_closed")`
 *    — same translation pattern as 4D.5 sign. Callers see a
 *    consistent 409 regardless of whether the race was
 *    sequential or concurrent.
 *
 * ### DB-layer defense-in-depth
 *
 * V21's `tr_clinical_encounter_immutable_once_closed` trigger
 * refuses any UPDATE on a row whose PRE-image has
 * `status IN ('FINISHED', 'CANCELLED')`. The transition itself
 * passes (OLD.status = 'IN_PROGRESS'); a double-finish that
 * bypassed this handler would be refused at the SQL layer.
 */
@Component
class FinishEncounterHandler(
    private val tenantRepository: TenantRepository,
    private val encounterRepository: EncounterRepository,
    private val encounterNoteRepository: EncounterNoteRepository,
    private val clock: Clock,
) {

    fun handle(
        command: FinishEncounterCommand,
        context: WriteContext,
    ): EncounterSnapshot {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val encounter = encounterRepository.findById(command.encounterId).orElse(null)
            ?: throw EntityNotFoundException(
                "encounter not found: ${command.encounterId}",
            )
        if (encounter.tenantId != tenant.id) {
            throw EntityNotFoundException(
                "encounter not found: ${command.encounterId}",
            )
        }

        if (encounter.status != EncounterStatus.IN_PROGRESS) {
            throw WriteConflictException("encounter_already_closed")
        }

        val signedCount = encounterNoteRepository.countSignedByEncounterId(encounter.id)
        if (signedCount == 0L) {
            throw WriteConflictException("encounter_has_no_signed_notes")
        }

        val now = Instant.now(clock)
        encounter.status = EncounterStatus.FINISHED
        encounter.finishedAt = now
        encounter.updatedAt = now
        encounter.updatedBy = context.principal.userId
        val saved = try {
            encounterRepository.saveAndFlush(encounter)
        } catch (ex: OptimisticLockingFailureException) {
            // Concurrent closer won the race; the row has
            // advanced past our snapshot. Translate to the
            // same 409 the sequential path throws so clients
            // see a consistent error body.
            throw WriteConflictException("encounter_already_closed", ex)
        }
        return toSnapshot(saved)
    }

    private fun toSnapshot(entity: EncounterEntity): EncounterSnapshot =
        EncounterSnapshot(
            id = entity.id,
            tenantId = entity.tenantId,
            patientId = entity.patientId,
            status = entity.status,
            encounterClass = entity.encounterClass,
            startedAt = entity.startedAt,
            finishedAt = entity.finishedAt,
            cancelledAt = entity.cancelledAt,
            cancelReason = entity.cancelReason,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            createdBy = entity.createdBy,
            updatedBy = entity.updatedBy,
            rowVersion = entity.rowVersion,
        )
}
