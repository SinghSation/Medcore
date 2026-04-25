package com.medcore.clinical.allergy.write

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
 * [WriteAuditor] for allergy updates (Phase 4E.1).
 *
 * Dispatches between two distinct audit actions based on the
 * handler outcome's `wasRevoke` flag:
 *
 *   - `wasRevoke = false` → CLINICAL_ALLERGY_UPDATED
 *     (clinical refinement: severity, reaction, onset, or
 *     ACTIVE↔INACTIVE status transition)
 *   - `wasRevoke = true` → CLINICAL_ALLERGY_REVOKED
 *     (terminal retraction: status moved to ENTERED_IN_ERROR)
 *
 * One PATCH endpoint, two audit actions — the wire surface
 * stays simple while compliance gets clean separation between
 * "this row was clinically refined" and "this row was
 * retracted as a mistake."
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Refinement success (UPDATED):**
 *   - action        = CLINICAL_ALLERGY_UPDATED
 *   - resource_type = "clinical.allergy"
 *   - resource_id   = allergy UUID
 *   - reason        = "intent:clinical.allergy.update|fields:<csv>"
 *     and, when the patch includes a status transition,
 *     "|status_from:<X>|status_to:<Y>" is appended.
 *
 * **Revocation success (REVOKED):**
 *   - action        = CLINICAL_ALLERGY_REVOKED
 *   - resource_type = "clinical.allergy"
 *   - resource_id   = allergy UUID
 *   - reason        = "intent:clinical.allergy.revoke|prior_status:<X>"
 *     where `<X>` is ACTIVE or INACTIVE (ENTERED_IN_ERROR
 *     can't be a prior status — terminal).
 *
 * **Denial (either outcome path):**
 *   - action        = AUTHZ_WRITE_DENIED
 *   - resource_type = "clinical.allergy"
 *   - resource_id   = command.allergyId.toString()
 *   - reason        = "intent:clinical.allergy.update|denial:<code>"
 *
 * ### No-op suppression
 *
 * `outcome.changed = false` → emit nothing. Covers two cases:
 *   - Standard "all patched fields equal current values" no-op.
 *   - Idempotent retry on an already-ENTERED_IN_ERROR row
 *     (handler returns `changed = false` rather than throwing
 *     `allergy_terminal` — terminal rows are immutable but
 *     idempotent re-revokes are a known UX concern, not an
 *     error).
 *
 * ### PHI discipline
 *
 * Reason carries field NAMES + closed-enum status tokens only.
 * Never the new reaction_text / severity values themselves
 * (well, severity values would be safe but we deliberately
 * keep audit slugs symmetric with the patient-update auditor:
 * names only, no values; before/after diffing waits for
 * Phase 7).
 */
@Component
class UpdateAllergyAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<UpdateAllergyCommand, UpdateAllergyOutcome> {

    override fun onSuccess(
        command: UpdateAllergyCommand,
        result: UpdateAllergyOutcome,
        context: WriteContext,
    ) {
        if (!result.changed) return

        if (result.wasRevoke) {
            auditWriter.write(
                AuditEventCommand(
                    action = AuditAction.CLINICAL_ALLERGY_REVOKED,
                    actorType = ActorType.USER,
                    actorId = context.principal.userId,
                    tenantId = result.snapshot.tenantId,
                    resourceType = RESOURCE_TYPE,
                    resourceId = result.snapshot.id.toString(),
                    outcome = AuditOutcome.SUCCESS,
                    reason = "$REVOKE_INTENT_SLUG|prior_status:${result.priorStatus.name}",
                ),
            )
            return
        }

        // Refinement path. fields:<csv> is the closed set of
        // changed field names; the order matches handler
        // declaration order (severity, reactionText, onsetDate,
        // status).
        val fields = result.changedFields.joinToString(",")
        val baseReason = "$UPDATE_INTENT_SLUG|fields:$fields"
        val reason = if ("status" in result.changedFields) {
            "$baseReason|status_from:${result.priorStatus.name}|" +
                "status_to:${result.snapshot.status.name}"
        } else {
            baseReason
        }
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_ALLERGY_UPDATED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = result.snapshot.tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = result.snapshot.id.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = reason,
            ),
        )
    }

    override fun onDenied(
        command: UpdateAllergyCommand,
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
                resourceId = command.allergyId.toString(),
                outcome = AuditOutcome.DENIED,
                reason = "$UPDATE_INTENT_SLUG|denial:${reason.code}",
            ),
        )
    }

    private companion object {
        const val RESOURCE_TYPE: String = "clinical.allergy"
        const val UPDATE_INTENT_SLUG: String = "intent:clinical.allergy.update"
        const val REVOKE_INTENT_SLUG: String = "intent:clinical.allergy.revoke"
    }
}
