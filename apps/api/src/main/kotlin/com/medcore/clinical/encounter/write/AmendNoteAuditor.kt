package com.medcore.clinical.encounter.write

import com.medcore.platform.audit.ActorType
import com.medcore.platform.audit.AuditAction
import com.medcore.platform.audit.AuditEventCommand
import com.medcore.platform.audit.AuditOutcome
import com.medcore.platform.audit.AuditWriter
import com.medcore.platform.write.WriteAuditor
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import org.springframework.stereotype.Component

/**
 * [WriteAuditor] for encounter-note amendments (Phase 4D.6).
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Success — inside the WriteGate tx:**
 *   - action        = CLINICAL_ENCOUNTER_NOTE_AMENDED
 *   - actor_type    = USER
 *   - actor_id      = caller userId (the amender)
 *   - tenant_id     = amendment note's tenantId
 *   - resource_type = "clinical.encounter.note"
 *   - resource_id   = the AMENDMENT's UUID — not the original
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.encounter.note.amend|originalNoteId:<uuid>"
 *
 * **Denial — outside tx:**
 *   - action        = AUTHZ_WRITE_DENIED
 *   - resource_type = "clinical.encounter.note"
 *   - resource_id   = null (denial before insert)
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.encounter.note.amend|denial:<code>"
 *
 * ### Why `originalNoteId` belongs in the reason
 *
 * Forensic reconstruction of an amendment chain — "what did this
 * note replace?" — needs both endpoints. `resource_id` carries
 * the amendment's UUID; embedding `originalNoteId` in the
 * reason slug means a single audit-row read can identify the
 * relationship without a join back to `clinical.encounter_note`
 * (which may have been re-amended in the meantime, though we
 * forbid amendment-of-amendment per the locked plan).
 *
 * Both UUIDs are non-PHI — they are randomly generated row
 * identifiers, not patient or content data. PHI (the note body)
 * never enters the audit row.
 *
 * ### Conflict path (409) — no audit row
 *
 * The handler emits [com.medcore.platform.write.WriteConflictException]
 * for three reasons:
 *   - `cannot_amend_unsigned_note` (original is DRAFT)
 *   - `cannot_amend_an_amendment` (single-level enforcement)
 *   - `amendment_integrity_violation` (V23 trigger fired on a
 *     race or post-handler bypass)
 * In every case, the WriteGate rolls back the transaction and
 * does NOT call `onSuccess`. State conflicts are not authz
 * failures, so `onDenied` is also not called. The 409 surfaces
 * via [com.medcore.platform.api.GlobalExceptionHandler] with
 * `details.reason` set to the conflict code.
 *
 * ### Naming: "amended" not "created"
 *
 * The amendment is *also* a row insert into
 * `clinical.encounter_note`. We deliberately do NOT emit a
 * second [AuditAction.CLINICAL_ENCOUNTER_NOTE_CREATED] row —
 * one event, one audit row. Forensic queries that want
 * "every note insert" can union over both actions; queries
 * that want "every legal-record correction" filter on
 * `CLINICAL_ENCOUNTER_NOTE_AMENDED` alone.
 */
@Component
class AmendNoteAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<AmendNoteCommand, EncounterNoteSnapshot> {

    override fun onSuccess(
        command: AmendNoteCommand,
        result: EncounterNoteSnapshot,
        context: WriteContext,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_ENCOUNTER_NOTE_AMENDED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = result.tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = result.id.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = "$INTENT_SLUG|originalNoteId:${command.originalNoteId}",
            ),
        )
    }

    override fun onDenied(
        command: AmendNoteCommand,
        context: WriteContext,
        reason: WriteDenialReason,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.AUTHZ_WRITE_DENIED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = null,
                resourceType = RESOURCE_TYPE,
                resourceId = null,
                outcome = AuditOutcome.DENIED,
                reason = "$INTENT_SLUG|denial:${reason.code}",
            ),
        )
    }

    private companion object {
        const val RESOURCE_TYPE: String = "clinical.encounter.note"
        const val INTENT_SLUG: String = "intent:clinical.encounter.note.amend"
    }
}
