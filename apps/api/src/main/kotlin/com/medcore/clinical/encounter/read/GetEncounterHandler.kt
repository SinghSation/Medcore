package com.medcore.clinical.encounter.read

import com.medcore.clinical.encounter.persistence.EncounterEntity
import com.medcore.clinical.encounter.persistence.EncounterRepository
import com.medcore.clinical.encounter.write.EncounterSnapshot
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.stereotype.Component

/**
 * Handler for [GetEncounterCommand] (Phase 4C.1, VS1 Chunk D).
 *
 * Runs inside [com.medcore.platform.read.ReadGate]'s transaction
 * with RLS GUCs set by `PhiRlsTxHook`. V18's `p_encounter_select`
 * filters cross-tenant rows and non-member visibility at the DB
 * layer.
 *
 * ### Flow
 *
 * 1. Load tenant by slug (invariant check — policy already ran).
 * 2. Load encounter by id. RLS-gated: a miss means "not found OR
 *    not visible". Identical 404 either way (existence-leak
 *    defence — same 4A.4 discipline).
 * 3. Belt-and-braces: verify `encounter.tenantId == tenant.id`.
 *    RLS already filters cross-tenant, but the app-layer check
 *    matches the 4A.2 / 4A.4 precedent.
 * 4. Return [EncounterSnapshot] built from the entity.
 */
@Component
class GetEncounterHandler(
    private val tenantRepository: TenantRepository,
    private val encounterRepository: EncounterRepository,
) {

    fun handle(
        command: GetEncounterCommand,
        @Suppress("UNUSED_PARAMETER") context: WriteContext,
    ): EncounterSnapshot {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val encounter = encounterRepository.findById(command.encounterId).orElse(null)
            ?: throw EntityNotFoundException("encounter not found: ${command.encounterId}")
        if (encounter.tenantId != tenant.id) {
            throw EntityNotFoundException("encounter not found: ${command.encounterId}")
        }

        return toSnapshot(encounter)
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
