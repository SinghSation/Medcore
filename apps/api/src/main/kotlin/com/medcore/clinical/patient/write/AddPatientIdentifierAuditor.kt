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
 * [WriteAuditor] for identifier add (Phase 4A.3).
 *
 * Follows `clinical-write-pattern.md` §6 NORMATIVE shape contract:
 * `[REQUIRED §6.1]` success row uses dedicated
 * [AuditAction.PATIENT_IDENTIFIER_ADDED]; denial row uses
 * [AuditAction.AUTHZ_WRITE_DENIED].
 * `[REQUIRED §6.3]` reason slug carries ONLY closed-enum tokens
 * (`type:<IDENTIFIER_TYPE>`) — never the `issuer` or `value`
 * fields.
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Success:**
 *   - action        = PATIENT_IDENTIFIER_ADDED
 *   - actor_type    = USER
 *   - actor_id      = caller userId
 *   - tenant_id     = target tenant UUID
 *   - resource_type = "clinical.patient.identifier"
 *   - resource_id   = new identifier UUID
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.patient.identifier.add|type:<TYPE>"
 *
 * **Denial (via `onDenied`):**
 *   - action        = AUTHZ_WRITE_DENIED
 *   - actor_id      = caller userId
 *   - tenant_id     = NULL (slug → tenantId may not have resolved
 *                           at denial time; matches 3J.N convention)
 *   - resource_type = "clinical.patient.identifier"
 *   - resource_id   = NULL (new identifier UUID does not yet exist
 *                           on the denial path — matches 4A.2
 *                           `CreatePatientAuditor` convention)
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.patient.identifier.add|denial:<reason-code>"
 *
 * ### PHI discipline
 *
 * The `type` slug is a closed-enum token (4 values). The
 * identifier's `value` (potentially PHI for DL/Insurance) is
 * NEVER in the reason. Forensic resolution to the specific
 * identifier goes through `resource_id` + an RLS-gated
 * SELECT — it does NOT land in the audit table itself.
 *
 * ### No-op suppression
 *
 * Add is never a no-op (every success persists a new row). The
 * `changed` flag on [PatientIdentifierSnapshot] is always `true`
 * for add. Unlike Revoke or 4A.2's UpdateDemographics, this
 * auditor does NOT need a `changed` check.
 */
@Component
class AddPatientIdentifierAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<AddPatientIdentifierCommand, PatientIdentifierSnapshot> {

    override fun onSuccess(
        command: AddPatientIdentifierCommand,
        result: PatientIdentifierSnapshot,
        context: WriteContext,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.PATIENT_IDENTIFIER_ADDED,
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
        command: AddPatientIdentifierCommand,
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
        const val RESOURCE_TYPE: String = "clinical.patient.identifier"
        const val INTENT_SLUG: String = "intent:clinical.patient.identifier.add"
    }
}
