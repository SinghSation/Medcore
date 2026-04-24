package com.medcore.clinical.encounter.write

import com.medcore.clinical.encounter.model.EncounterNoteStatus
import com.medcore.clinical.encounter.persistence.EncounterNoteEntity
import com.medcore.clinical.encounter.persistence.EncounterNoteRepository
import com.medcore.clinical.encounter.persistence.EncounterRepository
import com.medcore.platform.write.WriteConflictException
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import java.time.Instant
import org.springframework.stereotype.Component

/**
 * Handler for [SignEncounterNoteCommand] (Phase 4D.5).
 *
 * Runs inside the [com.medcore.platform.write.WriteGate]-owned
 * transaction after [SignEncounterNotePolicy] has approved
 * `NOTE_SIGN`. `PhiRlsTxHook` has set both RLS GUCs by the time
 * this handler runs.
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug (invariant — policy already checked
 *    ACTIVE membership).
 * 2. Load the parent encounter; verify `encounter.tenantId ==
 *    tenant.id`. Cross-tenant = 404 with no existence leak.
 * 3. Load the note; verify `note.tenantId == tenant.id` AND
 *    `note.encounterId == encounter.id`. Either mismatch ⇒ 404.
 *    A note posted to the wrong encounter URL MUST NOT leak
 *    its identity through a distinctive 4xx.
 * 4. If the note is already SIGNED, throw
 *    [WriteConflictException] with code `note_already_signed`
 *    → 409 `resource.conflict` with `details.reason:
 *    note_already_signed`. The 409 path is NOT idempotent —
 *    signing is a one-shot event.
 * 5. Transition the note: `status = SIGNED`, `signed_at = now()`,
 *    `signed_by = caller.userId`. Also update `updated_at` +
 *    `updated_by` in line with the platform's write auditing
 *    convention. `saveAndFlush` so `row_version` + enforced
 *    fields land before the auditor runs.
 * 6. Return [EncounterNoteSnapshot] reflecting the signed state.
 *
 * ### DB-layer defense-in-depth
 *
 * V20's trigger `tr_clinical_encounter_note_immutable_once_signed`
 * refuses UPDATEs to any row whose PRE-image has `status =
 * 'SIGNED'`. The signing transition itself passes (OLD.status =
 * 'DRAFT'); a re-sign attempt that somehow reached DB would be
 * refused at the SQL layer even if this handler's 409 check
 * were bypassed.
 */
@Component
class SignEncounterNoteHandler(
    private val tenantRepository: TenantRepository,
    private val encounterRepository: EncounterRepository,
    private val encounterNoteRepository: EncounterNoteRepository,
    private val clock: Clock,
) {

    fun handle(
        command: SignEncounterNoteCommand,
        context: WriteContext,
    ): EncounterNoteSnapshot {
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

        val note = encounterNoteRepository.findById(command.noteId).orElse(null)
            ?: throw EntityNotFoundException("note not found: ${command.noteId}")
        if (note.tenantId != tenant.id || note.encounterId != encounter.id) {
            throw EntityNotFoundException("note not found: ${command.noteId}")
        }

        if (note.status == EncounterNoteStatus.SIGNED) {
            throw WriteConflictException("note_already_signed")
        }

        val now = Instant.now(clock)
        note.status = EncounterNoteStatus.SIGNED
        note.signedAt = now
        note.signedBy = context.principal.userId
        note.updatedAt = now
        note.updatedBy = context.principal.userId
        val saved = encounterNoteRepository.saveAndFlush(note)
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
