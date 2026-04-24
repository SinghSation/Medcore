package com.medcore.clinical.encounter.read

import com.medcore.clinical.patient.persistence.PatientRepository
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
 * [ReadAuditor] for per-patient encounter list (Phase 4C.3).
 *
 * Follows the 4B.1 patient-list / 4D.1 note-list audit pattern:
 * one audit row per list call, including empty-list calls.
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Success — inside the ReadGate tx:**
 *   - action        = CLINICAL_ENCOUNTER_LIST_ACCESSED
 *   - actor_type    = USER
 *   - actor_id      = caller userId
 *   - tenant_id     = target tenant UUID (resolved via patient FK)
 *   - resource_type = "clinical.encounter"
 *   - resource_id   = null (list; no single target row)
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.encounter.list|count:N"
 *
 * **Denial — outside tx:**
 *   - action        = AUTHZ_READ_DENIED
 *   - tenant_id     = null
 *   - resource_type = "clinical.encounter"
 *   - resource_id   = null
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.encounter.list|denial:<code>"
 *
 * ### tenant_id on empty-list success
 *
 * The result's `items` is empty. The tenant UUID is resolved by
 * looking the parent patient up via [PatientRepository] — the
 * same patient the handler already verified is visible to the
 * caller. Forensic `WHERE tenant_id = …` queries will find the
 * audit row even when no encounters existed for the patient.
 * Mirrors [ListEncounterNotesAuditor] discipline (which resolves
 * tenant via the parent encounter).
 */
@Component
class ListPatientEncountersAuditor(
    private val auditWriter: AuditWriter,
    private val patientRepository: PatientRepository,
) : ReadAuditor<ListPatientEncountersCommand, ListPatientEncountersResult> {

    override fun onSuccess(
        command: ListPatientEncountersCommand,
        result: ListPatientEncountersResult,
        context: WriteContext,
    ) {
        // Tenant resolves from the parent patient — handler
        // already verified visibility so this lookup cannot fail
        // here. On rare drift we emit `tenant_id=null` rather
        // than throwing; a successful handler followed by a
        // missing-patient audit lookup is a data drift event
        // that should NOT roll back the read.
        val tenantId = patientRepository.findById(command.patientId)
            .orElse(null)
            ?.tenantId
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_ENCOUNTER_LIST_ACCESSED,
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
        command: ListPatientEncountersCommand,
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
        const val RESOURCE_TYPE: String = "clinical.encounter"
        const val INTENT_SLUG: String = "intent:clinical.encounter.list"
    }
}
