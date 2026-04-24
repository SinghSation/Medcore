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
 * [WriteAuditor] for encounter-note signing (Phase 4D.5).
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Success — inside the WriteGate tx:**
 *   - action        = CLINICAL_ENCOUNTER_NOTE_SIGNED
 *   - actor_type    = USER
 *   - actor_id      = caller userId (also the note's `signed_by`)
 *   - tenant_id     = signed note's tenantId
 *   - resource_type = "clinical.encounter.note"
 *   - resource_id   = signed note UUID
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.encounter.note.sign"
 *
 * **Denial — outside tx:**
 *   - action        = AUTHZ_WRITE_DENIED
 *   - resource_type = "clinical.encounter.note"
 *   - resource_id   = null (denial before state transition)
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.encounter.note.sign|denial:<code>"
 *
 * ### PHI discipline
 *
 * Reason slug carries only the fixed intent token. No body text,
 * no length, no hash. Forensic lookup of the signed content goes
 * through `resource_id` → the RLS-gated
 * `clinical.encounter_note` SELECT path.
 *
 * ### Conflict path (409)
 *
 * A re-sign attempt on an already-signed note raises
 * `WriteConflictException("note_already_signed")` from inside
 * the handler. The WriteGate catches the exception and rolls
 * back the transaction WITHOUT calling `onSuccess`. The exception
 * then surfaces through `GlobalExceptionHandler` as 409
 * `resource.conflict` + `details.reason: note_already_signed`.
 * No dedicated denial row for the 409 — it's not an authz
 * failure; it's a state conflict. The caller's client refetches
 * on 409 to reconcile.
 */
@Component
class SignEncounterNoteAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<SignEncounterNoteCommand, EncounterNoteSnapshot> {

    override fun onSuccess(
        command: SignEncounterNoteCommand,
        result: EncounterNoteSnapshot,
        context: WriteContext,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_ENCOUNTER_NOTE_SIGNED,
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
        command: SignEncounterNoteCommand,
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
        const val INTENT_SLUG: String = "intent:clinical.encounter.note.sign"
    }
}
