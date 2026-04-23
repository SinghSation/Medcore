package com.medcore.clinical.patient.read

import com.medcore.clinical.patient.write.PatientSnapshot
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
 * [ReadAuditor] for patient read (Phase 4A.4).
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Success (onSuccess) — emitted INSIDE read-only tx:**
 *   - action        = CLINICAL_PATIENT_ACCESSED
 *   - actor_type    = USER
 *   - actor_id      = caller userId
 *   - tenant_id     = disclosed patient's tenantId
 *   - resource_type = "clinical.patient"
 *   - resource_id   = disclosed patient UUID
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.patient.access"
 *
 * **Denial (onDenied) — emitted OUTSIDE tx via JdbcAuditWriter
 * PROPAGATION_REQUIRED:**
 *   - action        = AUTHZ_READ_DENIED
 *   - actor_id      = caller userId
 *   - tenant_id     = null
 *     (slug → tenantId may not have resolved at denial time;
 *      matches 4A.2 `CreatePatientAuditor` convention)
 *   - resource_type = "clinical.patient"
 *   - resource_id   = command.patientId (URL path carries it
 *     regardless of denial — unlike create denials where the
 *     UUID does not yet exist)
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.patient.access|denial:<code>"
 *
 * ### Atomicity
 *
 * Success emission runs inside the [ReadGate]'s read-only
 * transaction. Failure rolls the tx back, which prevents the
 * controller from serialising the response body — caller
 * receives 500, NO PHI disclosed. This is the compliance-
 * critical property (ADR-003 §2 extended to reads).
 *
 * ### No no-op suppression
 *
 * Every successful read discloses PHI and must be audited.
 * Unlike write auditors (where no-op writes don't persist
 * and thus don't audit), reads by definition return data
 * on success.
 *
 * ### PHI discipline
 *
 * The reason slug carries ONLY the fixed-string intent token.
 * Nothing from the disclosed patient (name, DOB, MRN,
 * demographics) appears in the audit row. Forensic lookup of
 * "what was disclosed?" goes through `resource_id` + an
 * RLS-gated SELECT, inheriting the clinical table's RLS
 * envelope.
 */
@Component
class GetPatientAuditor(
    private val auditWriter: AuditWriter,
) : ReadAuditor<GetPatientCommand, PatientSnapshot> {

    override fun onSuccess(
        command: GetPatientCommand,
        result: PatientSnapshot,
        context: WriteContext,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_PATIENT_ACCESSED,
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
        command: GetPatientCommand,
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
                resourceId = command.patientId.toString(),
                outcome = AuditOutcome.DENIED,
                reason = "$INTENT_SLUG|denial:${reason.code}",
            ),
        )
    }

    private companion object {
        const val RESOURCE_TYPE: String = "clinical.patient"
        const val INTENT_SLUG: String = "intent:clinical.patient.access"
    }
}
