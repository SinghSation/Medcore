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
 * [ReadAuditor] for per-patient encounter list (Phase 4C.3,
 * paginated as of platform-pagination chunk C, ADR-009).
 *
 * One audit row per list call — paginated calls produce one
 * row per page. The `count`, `page_size`, and `has_next` tokens
 * together let forensic queries reconstruct the multi-page
 * disclosure shape from a single row's reason slug.
 *
 * ### Audit-row shape contract (NORMATIVE — ADR-009 §2.7)
 *
 * **Success — inside the ReadGate tx:**
 *   - action        = CLINICAL_ENCOUNTER_LIST_ACCESSED
 *   - actor_type    = USER
 *   - actor_id      = caller userId
 *   - tenant_id     = target tenant UUID (resolved via patient FK)
 *   - resource_type = "clinical.encounter"
 *   - resource_id   = null (list; no single target row)
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.encounter.list|count:N|page_size:P|has_next:bool"
 *
 * **Denial — outside tx:**
 *   - action        = AUTHZ_READ_DENIED
 *   - tenant_id     = null
 *   - resource_type = "clinical.encounter"
 *   - resource_id   = null
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.encounter.list|denial:<code>"
 *
 * ### `count` semantic shift (NORMATIVE)
 *
 * Pre-pagination `count` was total disclosed rows. Post-
 * pagination it is per-page rows. Audit consumers MUST adapt;
 * an aggregation over `count` for a given `(actor_id,
 * resource_type, request_id)` triple sums to total disclosure.
 * ADR-009 §2.7.
 *
 * ### tenant_id on empty-list success
 *
 * Same discipline as chunks B / E — tenant resolves via the
 * parent patient. Mirrors [ListEncounterNotesAuditor].
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
        // ADR-009 §2.7: per-page count + page_size + has_next.
        val reason = buildString {
            append(INTENT_SLUG)
            append("|count:").append(result.items.size)
            append("|page_size:").append(command.pageRequest.pageSize)
            append("|has_next:").append(result.pageInfo.hasNextPage)
        }
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_ENCOUNTER_LIST_ACCESSED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = null,
                outcome = AuditOutcome.SUCCESS,
                reason = reason,
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
