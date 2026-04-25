package com.medcore.clinical.encounter.read

import com.medcore.clinical.encounter.persistence.EncounterRepository
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
 * [ReadAuditor] for encounter-note list (Phase 4D.1,
 * VS1 Chunk E; paginated as of platform-pagination chunk B,
 * ADR-009).
 *
 * One audit row per list call — paginated calls produce one
 * row per page (each page is a distinct disclosure event). The
 * `count`, `page_size`, and `has_next` tokens together let
 * forensic queries reconstruct the multi-page disclosure shape
 * without joining structured logs.
 *
 * ### Audit-row shape contract (NORMATIVE — ADR-009 §2.7)
 *
 * **Success — inside the ReadGate tx:**
 *   - action        = CLINICAL_ENCOUNTER_NOTE_LIST_ACCESSED
 *   - actor_type    = USER
 *   - actor_id      = caller userId
 *   - tenant_id     = parent encounter's tenant UUID
 *   - resource_type = "clinical.encounter.note"
 *   - resource_id   = null  (list; no single target row)
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.encounter.note.list|count:N|page_size:P|has_next:bool"
 *     where:
 *       - `count`     = items disclosed in THIS page (NOT total)
 *       - `page_size` = caller-requested pageSize
 *       - `has_next`  = `true` / `false`; `true` indicates a
 *                       sibling audit row will follow on the next
 *                       page-fetch
 *
 * **Denial — outside tx:**
 *   - action        = AUTHZ_READ_DENIED
 *   - tenant_id     = null
 *   - resource_type = "clinical.encounter.note"
 *   - resource_id   = null
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.encounter.note.list|denial:<code>"
 *
 * ### `count` semantic shift (NORMATIVE)
 *
 * Pre-pagination `count` was total disclosed rows. Post-
 * pagination it is per-page rows. Audit consumers + compliance
 * dashboards MUST adapt; an aggregation over `count` for a
 * given (actor_id, resource_type) pair sums to total disclosure.
 * ADR-009 §2.7 documents the shift.
 *
 * ### tenant_id on empty-list success
 *
 * The result's `items` is empty. The tenant UUID is resolved by
 * looking the encounter up via [EncounterRepository] — the same
 * encounter the handler already verified is visible to the
 * caller. Forensic `WHERE tenant_id = …` queries will find the
 * audit row even when no notes existed.
 */
@Component
class ListEncounterNotesAuditor(
    private val auditWriter: AuditWriter,
    private val encounterRepository: EncounterRepository,
) : ReadAuditor<ListEncounterNotesCommand, ListEncounterNotesResult> {

    override fun onSuccess(
        command: ListEncounterNotesCommand,
        result: ListEncounterNotesResult,
        context: WriteContext,
    ) {
        // Resolve tenant from the parent encounter — handler
        // already verified visibility so this lookup cannot fail
        // here. On the rare miss we emit `tenant_id=null` rather
        // than throwing; a successful handler followed by a
        // missing-encounter audit lookup is a data drift event
        // that should NOT roll back the read.
        val tenantId = encounterRepository.findById(command.encounterId)
            .orElse(null)
            ?.tenantId
        // ADR-009 §2.7: per-page count + page_size + has_next.
        // `count` is rows IN THIS PAGE (not total). Multi-page
        // disclosures emit one row per fetched page; aggregating
        // `count` over (actor_id, resource_type, request_id) sums
        // to the total disclosure.
        val reason = buildString {
            append(INTENT_SLUG)
            append("|count:").append(result.items.size)
            append("|page_size:").append(command.pageRequest.pageSize)
            append("|has_next:").append(result.pageInfo.hasNextPage)
        }
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_ENCOUNTER_NOTE_LIST_ACCESSED,
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
        command: ListEncounterNotesCommand,
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
        const val RESOURCE_TYPE: String = "clinical.encounter.note"
        const val INTENT_SLUG: String = "intent:clinical.encounter.note.list"
    }
}
