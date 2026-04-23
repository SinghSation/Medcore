package com.medcore.clinical.patient.read

import com.medcore.platform.audit.ActorType
import com.medcore.platform.audit.AuditAction
import com.medcore.platform.audit.AuditEventCommand
import com.medcore.platform.audit.AuditOutcome
import com.medcore.platform.audit.AuditWriter
import com.medcore.platform.read.ReadAuditor
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import com.medcore.tenancy.persistence.TenantRepository
import org.springframework.stereotype.Component

/**
 * [ReadAuditor] for patient list (Phase 4B.1).
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Success (onSuccess) — emitted INSIDE the ReadGate transaction:**
 *   - action        = CLINICAL_PATIENT_LIST_ACCESSED
 *   - actor_type    = USER
 *   - actor_id      = caller userId
 *   - tenant_id     = target tenant UUID
 *   - resource_type = "clinical.patient"
 *   - resource_id   = null  (list has no single target row)
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.patient.list|count:N|limit:L|offset:O"
 *
 * **Denial (onDenied) — emitted OUTSIDE tx via
 * `JdbcAuditWriter` PROPAGATION_REQUIRED:**
 *   - action        = AUTHZ_READ_DENIED
 *   - tenant_id     = null (slug → tenantId may not have resolved at
 *                      denial time; matches 4A.4 GetPatientAuditor)
 *   - resource_type = "clinical.patient"
 *   - resource_id   = null
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.patient.list|denial:<code>"
 *
 * ### PHI discipline (NORMATIVE)
 *
 * The `reason` slug carries only:
 *   - the fixed intent token
 *   - three bounded integers (`count`, `limit`, `offset`)
 *
 * Patient UUIDs, names, MRNs, and demographics NEVER appear in
 * the audit row. This is a deliberate choice approved in the
 * 4B.1 design pack:
 *   - Per-patient audit rows for a list would flood the log and
 *     make forensic review harder.
 *   - The `count` integer answers "how many rows did this call
 *     disclose?" which is the forensic question that matters.
 *   - Forensic resolution of "which rows?" goes through the
 *     request_id → structured-log join, inheriting the RLS
 *     envelope of `clinical.patient`.
 *
 * ### Single row per call
 *
 * One `CLINICAL_PATIENT_LIST_ACCESSED` row per list request
 * regardless of items.size — including for `count = 0`. A
 * legitimate list call that returns zero rows IS a disclosure
 * event (it reveals "there are no matching rows"), so the
 * audit row fires.
 *
 * ### Atomicity
 *
 * Success emission runs inside the [com.medcore.platform.read.ReadGate]
 * transaction. Failure rolls the tx back, which prevents the
 * controller from serialising the response body — caller
 * receives 500, NO PHI disclosed.
 *
 * ### Tenant resolution on success
 *
 * The `tenant_id` column is populated from the resolved tenant
 * by slug lookup on the handler's result. The result carries
 * `items[0].tenantId` when non-empty; when empty we resolve
 * the tenant slug once more via `TenantRepository.findBySlug`
 * so the audit row carries a tenant_id even for empty-result
 * lists (forensic queries `WHERE tenant_id = …` should find
 * every list call against that tenant).
 */
@Component
class ListPatientsAuditor(
    private val auditWriter: AuditWriter,
    private val tenantRepository: TenantRepository,
) : ReadAuditor<ListPatientsCommand, ListPatientsResult> {

    override fun onSuccess(
        command: ListPatientsCommand,
        result: ListPatientsResult,
        context: WriteContext,
    ) {
        val tenantId = result.items.firstOrNull()?.tenantId
            ?: tenantRepository.findBySlug(command.slug)?.id
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_PATIENT_LIST_ACCESSED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = null,
                outcome = AuditOutcome.SUCCESS,
                reason = buildSuccessReason(
                    count = result.items.size,
                    limit = result.limit,
                    offset = result.offset,
                ),
            ),
        )
    }

    override fun onDenied(
        command: ListPatientsCommand,
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

    private fun buildSuccessReason(count: Int, limit: Int, offset: Int): String =
        "$INTENT_SLUG|count:$count|limit:$limit|offset:$offset"

    private companion object {
        const val RESOURCE_TYPE: String = "clinical.patient"
        const val INTENT_SLUG: String = "intent:clinical.patient.list"
    }
}
