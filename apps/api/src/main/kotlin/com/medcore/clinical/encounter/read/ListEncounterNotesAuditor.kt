package com.medcore.clinical.encounter.read

import com.medcore.clinical.encounter.persistence.EncounterRepository
import com.medcore.platform.audit.ActorType
import com.medcore.platform.audit.AuditAction
import com.medcore.platform.audit.AuditEventCommand
import com.medcore.platform.audit.AuditOutcome
import com.medcore.platform.audit.AuditWriter
import com.medcore.platform.read.ReadAuditor
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import org.springframework.stereotype.Component

/**
 * [ReadAuditor] for encounter-note list (Phase 4D.1,
 * VS1 Chunk E). Follows the 4B.1 patient-list audit pattern:
 * one audit row per list call, including empty-list calls.
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Success — inside the ReadGate tx:**
 *   - action        = CLINICAL_ENCOUNTER_NOTE_LIST_ACCESSED
 *   - actor_type    = USER
 *   - actor_id      = caller userId
 *   - tenant_id     = parent encounter's tenant UUID
 *   - resource_type = "clinical.encounter.note"
 *   - resource_id   = null  (list; no single target row)
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.encounter.note.list|count:N"
 *
 * **Denial — outside tx:**
 *   - action        = AUTHZ_READ_DENIED
 *   - tenant_id     = null
 *   - resource_type = "clinical.encounter.note"
 *   - resource_id   = null
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.encounter.note.list|denial:<code>"
 *
 * ### tenant_id on empty-list success
 *
 * The result's `items` is empty. The tenant UUID is resolved by
 * looking the encounter up via [EncounterRepository] — the same
 * encounter the handler already verified is visible to the
 * caller. Forensic `WHERE tenant_id = …` queries will find the
 * audit row even when no notes existed.
 */
@Component
class ListEncounterNotesAuditor(
    private val auditWriter: AuditWriter,
    private val encounterRepository: EncounterRepository,
) : ReadAuditor<ListEncounterNotesCommand, ListEncounterNotesResult> {

    override fun onSuccess(
        command: ListEncounterNotesCommand,
        result: ListEncounterNotesResult,
        context: WriteContext,
    ) {
        // Resolve tenant from the parent encounter — handler
        // already verified visibility so this lookup cannot fail
        // here. On the rare miss we emit `tenant_id=null` rather
        // than throwing; a successful handler followed by a
        // missing-encounter audit lookup is a data drift event
        // that should NOT roll back the read.
        val tenantId = encounterRepository.findById(command.encounterId)
            .orElse(null)
            ?.tenantId
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_ENCOUNTER_NOTE_LIST_ACCESSED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = null,
                outcome = AuditOutcome.SUCCESS,
                reason = "$INTENT_SLUG|count:${result.items.size}",
            ),
        )
    }

    override fun onDenied(
        command: ListEncounterNotesCommand,
        context: WriteContext,
        reason: WriteDenialReason,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.AUTHZ_READ_DENIED,
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
        const val INTENT_SLUG: String = "intent:clinical.encounter.note.list"
    }
}
