package com.medcore.clinical.encounter.read

import com.medcore.clinical.encounter.persistence.EncounterNoteEntity
import com.medcore.clinical.encounter.persistence.EncounterNoteRepository
import com.medcore.clinical.encounter.persistence.EncounterRepository
import com.medcore.clinical.encounter.write.EncounterNoteSnapshot
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.stereotype.Component

/**
 * Handler for [ListEncounterNotesCommand] (Phase 4D.1,
 * VS1 Chunk E).
 *
 * Runs inside [com.medcore.platform.read.ReadGate]'s
 * transaction with both RLS GUCs set by `PhiRlsTxHook`.
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug.
 * 2. Load parent encounter; verify `encounter.tenantId ==
 *    tenant.id`. Cross-tenant = 404 (no existence leak).
 * 3. Fetch notes via
 *    [EncounterNoteRepository.findByEncounterIdOrdered]. The
 *    query runs under V19's SELECT RLS policy — rows the
 *    caller shouldn't see are filtered at the DB layer; a
 *    zero-row result means "no notes yet for this encounter"
 *    (a legitimate disclosure event that still emits an audit
 *    row via [ListEncounterNotesAuditor]).
 * 4. Map entities to [EncounterNoteSnapshot]s.
 */
@Component
class ListEncounterNotesHandler(
    private val tenantRepository: TenantRepository,
    private val encounterRepository: EncounterRepository,
    private val encounterNoteRepository: EncounterNoteRepository,
) {

    fun handle(
        command: ListEncounterNotesCommand,
        @Suppress("UNUSED_PARAMETER") context: WriteContext,
    ): ListEncounterNotesResult {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val encounter = encounterRepository.findById(command.encounterId).orElse(null)
            ?: throw EntityNotFoundException("encounter not found: ${command.encounterId}")
        if (encounter.tenantId != tenant.id) {
            throw EntityNotFoundException("encounter not found: ${command.encounterId}")
        }

        val notes = encounterNoteRepository.findByEncounterIdOrdered(encounter.id)
        return ListEncounterNotesResult(items = notes.map { toSnapshot(it) })
    }

    private fun toSnapshot(entity: EncounterNoteEntity): EncounterNoteSnapshot =
        EncounterNoteSnapshot(
            id = entity.id,
            tenantId = entity.tenantId,
            encounterId = entity.encounterId,
            body = entity.body,
            status = entity.status,
            signedAt = entity.signedAt,
            signedBy = entity.signedBy,
            amendsId = entity.amendsId,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            createdBy = entity.createdBy,
            updatedBy = entity.updatedBy,
            rowVersion = entity.rowVersion,
        )
}
