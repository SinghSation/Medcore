package com.medcore.clinical.encounter.write

import com.medcore.clinical.encounter.model.EncounterStatus
import com.medcore.clinical.encounter.persistence.EncounterNoteEntity
import com.medcore.clinical.encounter.persistence.EncounterNoteRepository
import com.medcore.clinical.encounter.persistence.EncounterRepository
import com.medcore.platform.write.WriteConflictException
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * Handler for [CreateEncounterNoteCommand] (Phase 4D.1,
 * VS1 Chunk E).
 *
 * Runs inside the [com.medcore.platform.write.WriteGate]-owned
 * transaction, after [CreateEncounterNotePolicy] has approved
 * `NOTE_WRITE`. `PhiRlsTxHook` has set both RLS GUCs
 * (`app.current_tenant_id` + `app.current_user_id`) by the
 * time this handler runs, so both repository calls + the
 * INSERT land under the caller's RLS envelope.
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug (invariant — policy already checked
 *    ACTIVE membership).
 * 2. Load parent encounter by id. Verify `encounter.tenantId ==
 *    tenant.id` — a cross-tenant probe returns 404 indistinguishable
 *    from "unknown encounter" (no existence leak). Mirrors 4A.4 +
 *    4C.1 belt-and-braces discipline.
 * 3. INSERT a new note row with `tenant_id` copied from the
 *    parent encounter (denormalized for V19 RLS policies),
 *    `body` from the (already-validated) command, and audit
 *    columns stamped from the clock + principal.
 * 4. Return [EncounterNoteSnapshot].
 *
 * ### Append-only contract
 *
 * No `findById` + `saveAndFlush` update path. Every call
 * mints a new UUID. The `row_version` is always 0 on insert.
 */
@Component
class CreateEncounterNoteHandler(
    private val tenantRepository: TenantRepository,
    private val encounterRepository: EncounterRepository,
    private val encounterNoteRepository: EncounterNoteRepository,
    private val clock: Clock,
) {

    fun handle(
        command: CreateEncounterNoteCommand,
        context: WriteContext,
    ): EncounterNoteSnapshot {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val encounter = encounterRepository.findById(command.encounterId).orElse(null)
            ?: throw EntityNotFoundException("encounter not found: ${command.encounterId}")
        if (encounter.tenantId != tenant.id) {
            throw EntityNotFoundException("encounter not found: ${command.encounterId}")
        }

        // Phase 4C.5: notes cannot be added to a closed encounter.
        // Closed (FINISHED / CANCELLED) encounters are part of the
        // legal medical record and are immutable. Any retroactive
        // documentation needs the future amendment workflow.
        if (encounter.status != EncounterStatus.IN_PROGRESS) {
            throw WriteConflictException("encounter_closed")
        }

        val now = Instant.now(clock)
        val entity = EncounterNoteEntity(
            id = UUID.randomUUID(),
            tenantId = encounter.tenantId,
            encounterId = encounter.id,
            body = command.body,
            createdAt = now,
            updatedAt = now,
            createdBy = context.principal.userId,
            updatedBy = context.principal.userId,
        )
        val saved = encounterNoteRepository.saveAndFlush(entity)
        return toSnapshot(saved)
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
