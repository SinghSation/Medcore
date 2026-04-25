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
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component

/**
 * Handler for [AmendNoteCommand] (Phase 4D.6).
 *
 * Creates a new DRAFT note that references an existing SIGNED
 * note via `amends_id`. The original is never touched — V20's
 * immutability trigger guarantees byte-stability of the legal
 * record even if a future write path tries an UPDATE.
 *
 * Runs inside the [com.medcore.platform.write.WriteGate]-owned
 * transaction, after [AmendNotePolicy] has approved
 * `NOTE_WRITE`. `PhiRlsTxHook` has set both RLS GUCs by the
 * time this handler runs.
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug (invariant — policy already checked
 *    ACTIVE membership).
 * 2. Load parent encounter; verify `encounter.tenantId ==
 *    tenant.id` (cross-tenant probe → 404, no existence leak).
 *    The encounter's status is NOT checked here — see "Encounter
 *    status" below.
 * 3. Load the original note; verify same tenant AND
 *    `note.encounterId == encounter.id` (cross-encounter probe
 *    → 404, no existence leak — same belt-and-braces discipline
 *    as 4D.5).
 * 4. If `original.status != SIGNED` → [WriteConflictException]
 *    `cannot_amend_unsigned_note`.
 * 5. If `original.amendsId != null` → [WriteConflictException]
 *    `cannot_amend_an_amendment` (single-level chains; sibling
 *    amendments on the same original are allowed).
 * 6. INSERT a new note row: `amends_id = originalNoteId`,
 *    `status = DRAFT`, `body = command.body`, `tenant_id` and
 *    `encounter_id` copied from the original (which is the same
 *    as the URL-path encounter, validated above).
 * 7. Return [EncounterNoteSnapshot].
 *
 * ### Encounter status — intentional asymmetry with CreateNote
 *
 * `CreateEncounterNoteHandler` refuses notes on closed
 * encounters (`encounter.status != IN_PROGRESS` ⇒ 409
 * `encounter_closed`) because new clinical documentation belongs
 * inside the encounter window.
 *
 * Amendments are exactly the opposite case — they exist to
 * correct or extend a SIGNED note AFTER the encounter closed.
 * Refusing amendments on closed encounters would defeat the
 * primary use case ("clinician realizes the note was wrong the
 * day after the visit"). The encounter stays in its current
 * state; amending does not reopen it.
 *
 * ### Race translation (defensive)
 *
 * The DB-level V23 trigger refuses INSERTs that violate single-
 * level or same-encounter invariants. Sequential paths are
 * caught by the handler's checks above and surface as clean
 * 409s. The catch on `DataIntegrityViolationException` covers a
 * race where two amendments INSERT at the same time and only
 * one passes the trigger, OR a future bug bypasses the handler
 * checks but is caught by the trigger.
 *
 * Same constraint as 4C.4: we do NOT re-query inside the catch
 * (the failed INSERT aborts the outer Postgres transaction;
 * SQLSTATE 25P02). Translation is to `WriteConflictException`
 * with code `amendment_integrity_violation` so the caller still
 * sees a structured 409 with a `reason` slug.
 */
@Component
class AmendNoteHandler(
    private val tenantRepository: TenantRepository,
    private val encounterRepository: EncounterRepository,
    private val encounterNoteRepository: EncounterNoteRepository,
    private val clock: Clock,
) {

    fun handle(
        command: AmendNoteCommand,
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

        val original = encounterNoteRepository.findById(command.originalNoteId).orElse(null)
            ?: throw EntityNotFoundException(
                "note not found: ${command.originalNoteId}",
            )
        if (original.tenantId != tenant.id || original.encounterId != encounter.id) {
            // Cross-tenant or cross-encounter probe: indistinguishable
            // 404 from "unknown note" — no existence leak.
            throw EntityNotFoundException(
                "note not found: ${command.originalNoteId}",
            )
        }

        if (original.status != EncounterNoteStatus.SIGNED) {
            throw WriteConflictException("cannot_amend_unsigned_note")
        }
        if (original.amendsId != null) {
            // Single-level only. Sibling amendments (multiple
            // amendments referencing the same original) are
            // allowed; chains of amendments are not.
            throw WriteConflictException("cannot_amend_an_amendment")
        }

        val now = Instant.now(clock)
        val entity = EncounterNoteEntity(
            id = UUID.randomUUID(),
            tenantId = tenant.id,
            encounterId = encounter.id,
            body = command.body,
            status = EncounterNoteStatus.DRAFT,
            signedAt = null,
            signedBy = null,
            amendsId = original.id,
            createdAt = now,
            updatedAt = now,
            createdBy = context.principal.userId,
            updatedBy = context.principal.userId,
        )
        val saved = try {
            encounterNoteRepository.saveAndFlush(entity)
        } catch (ex: DataIntegrityViolationException) {
            // V23's amendment-integrity trigger refused this
            // INSERT: either we hit a same-tx race past the
            // handler checks, or a future-bug bypassed them.
            // Translate to a 409 with the structured reason
            // slug — same shape callers see for the sequential
            // 409 paths above. We do NOT re-query: the failed
            // INSERT aborts the outer Postgres transaction
            // (SQLSTATE 25P02), so a SELECT on the same
            // EntityManager would itself fail.
            throw WriteConflictException(
                code = "amendment_integrity_violation",
                cause = ex,
            )
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
