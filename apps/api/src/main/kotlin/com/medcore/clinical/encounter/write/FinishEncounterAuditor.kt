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
 * [WriteAuditor] for encounter FINISH (Phase 4C.5).
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Success — inside the WriteGate tx:**
 *   - action        = CLINICAL_ENCOUNTER_FINISHED
 *   - actor_type    = USER
 *   - actor_id      = caller userId (also the encounter's
 *                     `updated_by`)
 *   - tenant_id     = finished encounter's tenantId
 *   - resource_type = "clinical.encounter"
 *   - resource_id   = finished encounter UUID
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.encounter.finish"
 *
 * **Denial — outside tx:**
 *   - action        = AUTHZ_WRITE_DENIED
 *   - resource_type = "clinical.encounter"
 *   - resource_id   = null
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.encounter.finish|denial:<code>"
 *
 * ### Conflict path (409)
 *
 * Two reasons both surface as `WriteConflictException`:
 *   - `encounter_already_closed` — double-finish or finishing
 *     a CANCELLED encounter.
 *   - `encounter_has_no_signed_notes` — FINISH precondition
 *     not met.
 *
 * Neither emits a denial row via this auditor;
 * `WriteConflictException` is NOT an authz failure. Client
 * refetches to reconcile, as documented on
 * `AuditAction.CLINICAL_ENCOUNTER_FINISHED`.
 */
@Component
class FinishEncounterAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<FinishEncounterCommand, EncounterSnapshot> {

    override fun onSuccess(
        command: FinishEncounterCommand,
        result: EncounterSnapshot,
        context: WriteContext,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_ENCOUNTER_FINISHED,
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
        command: FinishEncounterCommand,
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
        const val RESOURCE_TYPE: String = "clinical.encounter"
        const val INTENT_SLUG: String = "intent:clinical.encounter.finish"
    }
}
