package com.medcore.clinical.patient.write

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
 * [WriteAuditor] for identifier revoke (Phase 4A.3).
 *
 * Mirrors [AddPatientIdentifierAuditor]; key differences:
 *
 * - Action is [AuditAction.PATIENT_IDENTIFIER_REVOKED].
 * - `resource_id` on denial is the target identifier UUID
 *   (the URL path carries it regardless of denial) — NOT null.
 *   Matches 3J.N `UpdateTenantMembershipRoleAuditor` convention.
 * - `changed` is checked: idempotent DELETE-on-already-revoked
 *   emits no audit row. `clinical-write-pattern.md` §6.4 no-op
 *   suppression.
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Success:**
 *   - action        = PATIENT_IDENTIFIER_REVOKED
 *   - actor_type    = USER
 *   - actor_id      = caller userId
 *   - tenant_id     = target tenant UUID
 *   - resource_type = "clinical.patient.identifier"
 *   - resource_id   = target identifier UUID
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.patient.identifier.revoke|type:<TYPE>"
 *
 * **Denial:**
 *   - action        = AUTHZ_WRITE_DENIED
 *   - resource_type = "clinical.patient.identifier"
 *   - resource_id   = command.identifierId (known from path)
 *   - reason        = "intent:clinical.patient.identifier.revoke|denial:<code>"
 *
 * **Suppressed on no-op** (already-revoked target).
 */
@Component
class RevokePatientIdentifierAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<RevokePatientIdentifierCommand, PatientIdentifierSnapshot> {

    override fun onSuccess(
        command: RevokePatientIdentifierCommand,
        result: PatientIdentifierSnapshot,
        context: WriteContext,
    ) {
        if (!result.changed) return
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.PATIENT_IDENTIFIER_REVOKED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = result.tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = result.id.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = "$INTENT_SLUG|type:${result.type}",
            ),
        )
    }

    override fun onDenied(
        command: RevokePatientIdentifierCommand,
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
                resourceId = command.identifierId.toString(),
                outcome = AuditOutcome.DENIED,
                reason = "$INTENT_SLUG|denial:${reason.code}",
            ),
        )
    }

    private companion object {
        const val RESOURCE_TYPE: String = "clinical.patient.identifier"
        const val INTENT_SLUG: String = "intent:clinical.patient.identifier.revoke"
    }
}
