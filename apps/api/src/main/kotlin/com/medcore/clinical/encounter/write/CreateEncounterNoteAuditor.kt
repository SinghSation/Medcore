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
 * [WriteAuditor] for encounter-note create (Phase 4D.1,
 * VS1 Chunk E).
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Success — inside the WriteGate tx:**
 *   - action        = CLINICAL_ENCOUNTER_NOTE_CREATED
 *   - actor_type    = USER
 *   - actor_id      = caller userId
 *   - tenant_id     = parent encounter's tenantId (denormalized
 *                     on the note row, asserted by handler)
 *   - resource_type = "clinical.encounter.note"
 *   - resource_id   = newly-minted note UUID
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.encounter.note.create"
 *
 * **Denial — outside tx:**
 *   - action        = AUTHZ_WRITE_DENIED
 *   - resource_type = "clinical.encounter.note"
 *   - resource_id   = null  (no note created on denial)
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.encounter.note.create|denial:<code>"
 *
 * ### PHI discipline (NORMATIVE)
 *
 * The note body is PHI. It NEVER appears in the audit row —
 * not the body text, not the character count, not a length
 * bucket, not a hash, not any derived metadata that could
 * narrow the body's identity. Forensic lookup of "what did
 * the author write?" goes through `resource_id` → the
 * RLS-gated SELECT on `clinical.encounter_note`.
 */
@Component
class CreateEncounterNoteAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<CreateEncounterNoteCommand, EncounterNoteSnapshot> {

    override fun onSuccess(
        command: CreateEncounterNoteCommand,
        result: EncounterNoteSnapshot,
        context: WriteContext,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_ENCOUNTER_NOTE_CREATED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = result.tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = result.id.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = INTENT_SLUG,
            ),
        )
    }

    override fun onDenied(
        command: CreateEncounterNoteCommand,
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
        const val INTENT_SLUG: String = "intent:clinical.encounter.note.create"
    }
}
