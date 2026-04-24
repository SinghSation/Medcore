package com.medcore.clinical.encounter.write

import com.medcore.clinical.encounter.model.EncounterStatus
import com.medcore.clinical.encounter.persistence.EncounterEntity
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
 * Handler for [CancelEncounterCommand] (Phase 4C.5).
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug.
 * 2. Load encounter; verify `encounter.tenantId == tenant.id`.
 *    404 identity discipline (unknown and cross-tenant both 404,
 *    no existence leak).
 * 3. If `status != IN_PROGRESS` →
 *    [WriteConflictException]`("encounter_already_closed")`
 *    → 409 `resource.conflict`.
 * 4. Transition: `status = CANCELLED`, `cancelled_at = now()`,
 *    `cancel_reason = command.cancelReason`, `updated_at`,
 *    `updated_by`. `saveAndFlush`.
 * 5. On race (optimistic-lock at saveAndFlush), translate to
 *    [WriteConflictException]`("encounter_already_closed")`
 *    — same pattern as the 4D.5 sign handler.
 *
 * Cancel does NOT have the "≥ 1 signed note" precondition that
 * finish does — a cancelled encounter represents a visit that
 * didn't happen (no-show, scheduling error) or was aborted
 * (patient declined). There may be no notes at all, and
 * that's fine.
 */
@Component
class CancelEncounterHandler(
    private val tenantRepository: TenantRepository,
    private val encounterRepository: EncounterRepository,
    private val clock: Clock,
) {

    fun handle(
        command: CancelEncounterCommand,
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

        val now = Instant.now(clock)
        encounter.status = EncounterStatus.CANCELLED
        encounter.cancelledAt = now
        encounter.cancelReason = command.cancelReason
        encounter.updatedAt = now
        encounter.updatedBy = context.principal.userId
        val saved = try {
            encounterRepository.saveAndFlush(encounter)
        } catch (ex: OptimisticLockingFailureException) {
            throw WriteConflictException("encounter_already_closed", ex)
        }
        return EncounterSnapshot.from(saved)
    }
}
