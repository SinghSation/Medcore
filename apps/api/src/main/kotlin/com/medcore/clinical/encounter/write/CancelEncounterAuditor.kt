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
 * [WriteAuditor] for encounter CANCEL (Phase 4C.5).
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Success — inside the WriteGate tx:**
 *   - action        = CLINICAL_ENCOUNTER_CANCELLED
 *   - actor_type    = USER
 *   - actor_id      = caller userId
 *   - tenant_id     = cancelled encounter's tenantId
 *   - resource_type = "clinical.encounter"
 *   - resource_id   = cancelled encounter UUID
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.encounter.cancel|reason:<CLOSED_ENUM>"
 *     where `<CLOSED_ENUM>` is one of the
 *     [com.medcore.clinical.encounter.model.CancelReason] tokens
 *     (e.g., `NO_SHOW`, `PATIENT_DECLINED`).
 *
 * **Denial — outside tx:**
 *   - action        = AUTHZ_WRITE_DENIED
 *   - resource_type = "clinical.encounter"
 *   - resource_id   = null
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.encounter.cancel|denial:<code>"
 *
 * ### PHI discipline
 *
 * The `cancel_reason` token is closed-enum and safe in audit
 * slugs. No patient / encounter identifiers are embedded in the
 * reason beyond the fixed intent + closed-enum reason code.
 * Forensic resolution of "which encounter was cancelled?" goes
 * through `resource_id`.
 */
@Component
class CancelEncounterAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<CancelEncounterCommand, EncounterSnapshot> {

    override fun onSuccess(
        command: CancelEncounterCommand,
        result: EncounterSnapshot,
        context: WriteContext,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_ENCOUNTER_CANCELLED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = result.tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = result.id.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = "$INTENT_SLUG|reason:${command.cancelReason.name}",
            ),
        )
    }

    override fun onDenied(
        command: CancelEncounterCommand,
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
        const val INTENT_SLUG: String = "intent:clinical.encounter.cancel"
    }
}
