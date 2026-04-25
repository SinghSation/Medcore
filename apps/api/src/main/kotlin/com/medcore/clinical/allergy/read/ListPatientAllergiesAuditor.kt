package com.medcore.clinical.allergy.read

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
 * [ReadAuditor] for the per-patient allergy list (Phase 4E.1).
 *
 * Mirrors the 4C.3 patient-encounter-list and 4D.1 note-list
 * patterns: one audit row per list call, including empty-list
 * calls.
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Success — inside the ReadGate tx (paginated as of
 * platform-pagination chunk D, ADR-009 §2.7):**
 *   - action        = CLINICAL_ALLERGY_LIST_ACCESSED
 *   - actor_type    = USER
 *   - actor_id      = caller userId
 *   - tenant_id     = target tenant UUID (resolved via patient FK)
 *   - resource_type = "clinical.allergy"
 *   - resource_id   = null (list — no single target row)
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.allergy.list|count:N|page_size:P|has_next:bool"
 *
 * `count` is per-page rows (NOT total). Multi-page disclosures
 * emit one row per page. Aggregating `count` over a triple
 * `(actor_id, resource_type, request_id)` sums to total
 * disclosure (ADR-009 §2.7).
 *
 * **Denial — outside tx:**
 *   - action        = AUTHZ_READ_DENIED
 *   - tenant_id     = null
 *   - resource_type = "clinical.allergy"
 *   - resource_id   = null
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.allergy.list|denial:<code>"
 *
 * ### tenant_id on empty-list success
 *
 * The result's `items` may be empty (legitimate disclosure:
 * "this patient has no allergies recorded"). We resolve the
 * tenant from the parent patient (the same one the handler
 * already verified is visible to the caller) so forensic
 * `WHERE tenant_id = …` queries find the audit row even when
 * the allergy slate is empty. Mirrors
 * [com.medcore.clinical.encounter.read.ListPatientEncountersAuditor]
 * discipline.
 */
@Component
class ListPatientAllergiesAuditor(
    private val auditWriter: AuditWriter,
    private val patientRepository: PatientRepository,
) : ReadAuditor<ListPatientAllergiesCommand, ListPatientAllergiesResult> {

    override fun onSuccess(
        command: ListPatientAllergiesCommand,
        result: ListPatientAllergiesResult,
        context: WriteContext,
    ) {
        // Tenant resolves from the parent patient — handler
        // already verified visibility so this lookup cannot
        // fail. On rare drift we emit `tenant_id = null` rather
        // than throwing; a successful handler followed by a
        // missing-patient audit lookup is data drift that
        // should NOT roll back the read.
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
                action = AuditAction.CLINICAL_ALLERGY_LIST_ACCESSED,
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
        command: ListPatientAllergiesCommand,
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
        const val RESOURCE_TYPE: String = "clinical.allergy"
        const val INTENT_SLUG: String = "intent:clinical.allergy.list"
    }
}
