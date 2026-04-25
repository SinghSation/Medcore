package com.medcore.clinical.encounter.write

import com.medcore.clinical.encounter.model.EncounterNoteStatus
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
import org.springframework.dao.OptimisticLockingFailureException
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
 * ### Concurrent sign race
 *
 * Two simultaneous sign requests on the same DRAFT note can both
 * pass the `status == SIGNED` check — check-then-update is not
 * atomic without row-level locking. The losing transaction then
 * fails at `saveAndFlush` because JPA's `@Version` optimistic
 * lock refuses to update a row whose `row_version` has already
 * advanced. Without translation, that surfaces as
 * [OptimisticLockingFailureException] →
 * [com.medcore.platform.api.GlobalExceptionHandler]'s generic
 * 409 `resource.conflict`. Concurrent re-signs would then emit
 * a different error body than the documented sequential re-sign
 * (`details.reason: note_already_signed`).
 *
 * Fix: catch the optimistic-lock exception around `saveAndFlush`
 * and re-throw as [WriteConflictException] with the same
 * `note_already_signed` code. Sequential and concurrent
 * re-signs now surface identically to the caller.
 *
 * ### DB-layer defense-in-depth
 *
 * V20's trigger `tr_clinical_encounter_note_immutable_once_signed`
 * refuses UPDATEs to any row whose PRE-image has `status =
 * 'SIGNED'`. The signing transition itself passes (OLD.status =
 * 'DRAFT'); a re-sign attempt that somehow reached DB would be
 * refused at the SQL layer even if this handler's 409 check
 * were bypassed.
 *
 * ### Closed-encounter rule (Phase 4C.5 + 4D.6 carve-out)
 *
 * - Non-amendment DRAFTs (`note.amendsId IS NULL`) on a closed
 *   encounter (FINISHED / CANCELLED) → 409 `encounter_closed`.
 *   The encounter itself is immutable post-close; new clinical
 *   documentation belongs inside the encounter window.
 * - Amendments (`note.amendsId IS NOT NULL`) → may be signed
 *   regardless of the encounter's status. That carve-out is
 *   the *entire reason* the 4D.6 amendment workflow exists:
 *   to write SIGNED corrections to the legal record AFTER the
 *   encounter has closed. Signing an amendment does NOT reopen
 *   the parent encounter; the encounter stays in its current
 *   closed state.
 *
 * The check sits AFTER the note load (not before) because the
 * carve-out keys on `note.amendsId` — a column on the note row,
 * not the encounter. Reordering does not change the 404 surface
 * for cross-tenant or cross-encounter probes; it just lets us
 * read `amendsId` before deciding whether the closed-encounter
 * rule applies.
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

        // Phase 4C.5 closed-encounter rule, refined in Phase 4D.6:
        //
        //   - Non-amendment notes (`amendsId IS NULL`): may be
        //     signed only while the encounter is IN_PROGRESS.
        //     After FINISH / CANCEL the encounter is immutable
        //     and a still-DRAFT regular note can never be signed.
        //   - Amendments (`amendsId IS NOT NULL`): MAY be signed
        //     regardless of the encounter's status. The whole
        //     point of the 4D.6 amendment workflow is to write
        //     SIGNED corrections to the legal record AFTER the
        //     encounter window has closed. The encounter itself
        //     stays in its current closed state — signing the
        //     amendment does not reopen it.
        //
        // The carve-out is explicit and deterministic: it keys on
        // a row column (`note.amendsId`), not on caller intent or
        // a separate authority. Same `WriteConflictException`
        // contract preserved for non-amendment closed-encounter
        // attempts (`details.reason: encounter_closed`).
        if (
            encounter.status != EncounterStatus.IN_PROGRESS &&
            note.amendsId == null
        ) {
            throw WriteConflictException("encounter_closed")
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
        val saved = try {
            encounterNoteRepository.saveAndFlush(note)
        } catch (ex: OptimisticLockingFailureException) {
            // A concurrent signer won the race: the row_version on
            // disk has advanced past what this tx loaded, so the
            // note is already SIGNED. Translate to the same
            // WriteConflictException the sequential path throws so
            // callers see a consistent 409 + reason.
            throw WriteConflictException("note_already_signed", ex)
        }
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
