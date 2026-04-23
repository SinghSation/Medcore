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
 * [WriteAuditor] for encounter start (Phase 4C.1, VS1 Chunk D).
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Success (onSuccess) — emitted INSIDE the WriteGate tx:**
 *   - action        = CLINICAL_ENCOUNTER_STARTED
 *   - actor_type    = USER
 *   - actor_id      = caller userId
 *   - tenant_id     = started encounter's tenantId
 *   - resource_type = "clinical.encounter"
 *   - resource_id   = newly-minted encounter UUID
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.encounter.start|class:<CLASS>"
 *
 * **Denial (onDenied) — emitted OUTSIDE tx:**
 *   - action        = AUTHZ_WRITE_DENIED
 *   - resource_type = "clinical.encounter"
 *   - resource_id   = null  (no encounter created on denial)
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.encounter.start|denial:<code>"
 *
 * ### PHI discipline
 *
 * Reason slug carries ONLY the intent token + the `class` closed-enum.
 * Patient UUID, tenant UUID, timestamps — none embedded in the
 * reason. Forensic linkage goes through `resource_id` →
 * `clinical.encounter.patient_id`, inheriting the table's RLS
 * envelope.
 */
@Component
class StartEncounterAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<StartEncounterCommand, EncounterSnapshot> {

    override fun onSuccess(
        command: StartEncounterCommand,
        result: EncounterSnapshot,
        context: WriteContext,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_ENCOUNTER_STARTED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = result.tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = result.id.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = "$INTENT_SLUG|class:${result.encounterClass.name}",
            ),
        )
    }

    override fun onDenied(
        command: StartEncounterCommand,
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
        const val INTENT_SLUG: String = "intent:clinical.encounter.start"
    }
}
