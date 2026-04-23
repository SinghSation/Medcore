package com.medcore.clinical.encounter.read

import com.medcore.clinical.encounter.write.EncounterSnapshot
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
 * [ReadAuditor] for encounter read (Phase 4C.1, VS1 Chunk D).
 *
 * ### Audit-row shape (NORMATIVE)
 *
 * **Success — inside the ReadGate tx:**
 *   - action        = CLINICAL_ENCOUNTER_ACCESSED
 *   - actor_type    = USER
 *   - actor_id      = caller userId
 *   - tenant_id     = disclosed encounter's tenantId
 *   - resource_type = "clinical.encounter"
 *   - resource_id   = disclosed encounter UUID
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.encounter.access"
 *
 * **Denial — outside tx:**
 *   - action        = AUTHZ_READ_DENIED
 *   - tenant_id     = null (slug → tenantId may not have resolved)
 *   - resource_type = "clinical.encounter"
 *   - resource_id   = command.encounterId
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.encounter.access|denial:<code>"
 *
 * Emission discipline + PHI posture mirror 4A.4
 * `GetPatientAuditor`: emit on 200 only; rolled back tx = no
 * disclosure = no audit; reason carries only closed-set tokens.
 */
@Component
class GetEncounterAuditor(
    private val auditWriter: AuditWriter,
) : ReadAuditor<GetEncounterCommand, EncounterSnapshot> {

    override fun onSuccess(
        command: GetEncounterCommand,
        result: EncounterSnapshot,
        context: WriteContext,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_ENCOUNTER_ACCESSED,
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
        command: GetEncounterCommand,
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
                resourceId = command.encounterId.toString(),
                outcome = AuditOutcome.DENIED,
                reason = "$INTENT_SLUG|denial:${reason.code}",
            ),
        )
    }

    private companion object {
        const val RESOURCE_TYPE: String = "clinical.encounter"
        const val INTENT_SLUG: String = "intent:clinical.encounter.access"
    }
}
