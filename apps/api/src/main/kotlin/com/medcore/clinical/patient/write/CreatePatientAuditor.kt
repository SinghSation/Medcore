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
 * [WriteAuditor] for patient create (Phase 4A.2).
 *
 * ## Audit-row shape contract (NORMATIVE — first PHI-bearing action)
 *
 * **Success row:**
 *   - `action` = `PATIENT_CREATED` (`clinical.patient.created`)
 *   - `actor_type` = `USER`
 *   - `actor_id` = caller's userId
 *   - `tenant_id` = target tenant UUID
 *   - `resource_type` = `"clinical.patient"`
 *   - `resource_id` = newly-minted patient UUID (stringified)
 *   - `outcome` = `SUCCESS`
 *   - `reason` = `intent:clinical.patient.create|mrn_source:GENERATED`
 *
 * **Denial row (policy refusal):**
 *   - `action` = `AUTHZ_WRITE_DENIED`
 *   - `actor_id` = caller's userId
 *   - `tenant_id` = NULL (slug → tenantId mapping may not be
 *     resolvable on denial; matches 3J.N denial-row convention)
 *   - `resource_type` = `"clinical.patient"`
 *   - `resource_id` = NULL — no target patient UUID exists on a
 *     denied create (parallel to 3J.3 invite denials)
 *   - `outcome` = `DENIED`
 *   - `reason` = `intent:clinical.patient.create|denial:<reason-code>`
 *
 * ## PHI discipline — what is NOT in the audit row
 *
 *   - Patient name parts — NEVER.
 *   - Birth date — NEVER.
 *   - Administrative sex / gender identity — NEVER.
 *   - Preferred language — NEVER.
 *
 * The `resource_id` alone identifies the patient. Any forensic
 * lookup joins back to `clinical.patient` via that UUID, inheriting
 * the clinical table's RLS envelope. This preserves the ADR-003 §3
 * flat-schema discipline — every field in `reason` is a closed-enum
 * token or a UUID.
 *
 * Write-time payload diffing (before/after snapshots) is a Phase 7
 * audit-schema-evolution concern, tracked as a carry-forward.
 *
 * ## Queries
 *
 * ```sql
 * -- All patient creates by a specific user:
 * SELECT * FROM audit.audit_event
 *  WHERE action = 'clinical.patient.created'
 *    AND actor_id = :userId
 *  ORDER BY occurred_at;
 *
 * -- All denied creates in a tenant:
 * SELECT * FROM audit.audit_event
 *  WHERE action = 'authz.write.denied'
 *    AND reason LIKE 'intent:clinical.patient.create|denial:%';
 * ```
 */
@Component
class CreatePatientAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<CreatePatientCommand, PatientSnapshot> {

    override fun onSuccess(
        command: CreatePatientCommand,
        result: PatientSnapshot,
        context: WriteContext,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.PATIENT_CREATED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = result.tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = result.id.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = "$INTENT_SLUG|mrn_source:${result.mrnSource}",
            ),
        )
    }

    override fun onDenied(
        command: CreatePatientCommand,
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
        const val RESOURCE_TYPE: String = "clinical.patient"
        const val INTENT_SLUG: String = "intent:clinical.patient.create"
    }
}
