package com.medcore.clinical.problem.write

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
 * [WriteAuditor] for problem updates (Phase 4E.2).
 *
 * Three-way dispatch on the handler outcome's `kind`:
 *
 *   - `UPDATED`  → CLINICAL_PROBLEM_UPDATED   (target ACTIVE
 *                  or INACTIVE; includes RESOLVED → ACTIVE
 *                  recurrence)
 *   - `RESOLVED` → CLINICAL_PROBLEM_RESOLVED  (target RESOLVED)
 *   - `REVOKED`  → CLINICAL_PROBLEM_REVOKED   (target
 *                  ENTERED_IN_ERROR)
 *
 * One PATCH endpoint, three audit actions — the wire surface
 * stays simple while compliance gets clean separation between
 * "this row was clinically refined", "this problem resolved",
 * and "this row was retracted as a mistake."
 *
 * **RESOLVED ≠ INACTIVE** — see [com.medcore.clinical.problem.model.ProblemStatus]
 * KDoc. The dedicated `CLINICAL_PROBLEM_RESOLVED` action and
 * the explicit `kind` field on the handler outcome both encode
 * this distinction. Collapsing the two would corrupt clinical-
 * outcome reporting and reduce a load-bearing audit token to a
 * meaningless flag.
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Refinement success (UPDATED):**
 *   - action        = CLINICAL_PROBLEM_UPDATED
 *   - resource_type = "clinical.problem"
 *   - resource_id   = problem UUID
 *   - reason        = "intent:clinical.problem.update|fields:<csv>"
 *     and, when the patch includes a status transition,
 *     "|status_from:<X>|status_to:<Y>" is appended where
 *     `<Y>` ∈ ACTIVE / INACTIVE. (Recurrence: `<X>` =
 *     RESOLVED, `<Y>` = ACTIVE — preserved in the slug.)
 *
 * **Resolution success (RESOLVED):**
 *   - action        = CLINICAL_PROBLEM_RESOLVED
 *   - resource_type = "clinical.problem"
 *   - resource_id   = problem UUID
 *   - reason        = "intent:clinical.problem.resolve|prior_status:<X>"
 *     where `<X>` ∈ ACTIVE / INACTIVE.
 *
 * **Revocation success (REVOKED):**
 *   - action        = CLINICAL_PROBLEM_REVOKED
 *   - resource_type = "clinical.problem"
 *   - resource_id   = problem UUID
 *   - reason        = "intent:clinical.problem.revoke|prior_status:<X>"
 *     where `<X>` ∈ ACTIVE / INACTIVE / RESOLVED
 *     (ENTERED_IN_ERROR can't be a prior status — terminal).
 *
 * **Denial (any kind):**
 *   - action        = AUTHZ_WRITE_DENIED
 *   - resource_type = "clinical.problem"
 *   - resource_id   = command.problemId.toString()
 *   - reason        = "intent:clinical.problem.update|denial:<code>"
 *
 * ### No-op suppression
 *
 * `outcome.changed = false` → emit nothing. Covers two cases:
 *   - Standard "all patched fields equal current values" no-op.
 *   - Idempotent retry on an already-ENTERED_IN_ERROR row
 *     (handler returns `kind = UPDATED, changed = false`
 *     rather than throwing `problem_terminal` — terminal rows
 *     are immutable but idempotent re-revokes are a known UX
 *     concern, not an error).
 *
 * ### PHI discipline
 *
 * Reason carries field NAMES + closed-enum status tokens only.
 * Never the new severity / date values themselves; before/
 * after diffing waits for the Phase 7 audit-schema-evolution
 * ADR.
 */
@Component
class UpdateProblemAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<UpdateProblemCommand, UpdateProblemOutcome> {

    override fun onSuccess(
        command: UpdateProblemCommand,
        result: UpdateProblemOutcome,
        context: WriteContext,
    ) {
        if (!result.changed) return

        when (result.kind) {
            UpdateProblemOutcome.Kind.RESOLVED -> emitResolved(result, context)
            UpdateProblemOutcome.Kind.REVOKED -> emitRevoked(result, context)
            UpdateProblemOutcome.Kind.UPDATED -> emitUpdated(result, context)
        }
    }

    private fun emitUpdated(
        result: UpdateProblemOutcome,
        context: WriteContext,
    ) {
        // Refinement path. fields:<csv> is the closed set of
        // changed field names; the order matches the handler
        // declaration order (severity, onsetDate,
        // abatementDate, status).
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
                action = AuditAction.CLINICAL_PROBLEM_UPDATED,
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

    private fun emitResolved(
        result: UpdateProblemOutcome,
        context: WriteContext,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_PROBLEM_RESOLVED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = result.snapshot.tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = result.snapshot.id.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = "$RESOLVE_INTENT_SLUG|prior_status:${result.priorStatus.name}",
            ),
        )
    }

    private fun emitRevoked(
        result: UpdateProblemOutcome,
        context: WriteContext,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_PROBLEM_REVOKED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = result.snapshot.tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = result.snapshot.id.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = "$REVOKE_INTENT_SLUG|prior_status:${result.priorStatus.name}",
            ),
        )
    }

    override fun onDenied(
        command: UpdateProblemCommand,
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
                resourceId = command.problemId.toString(),
                outcome = AuditOutcome.DENIED,
                reason = "$UPDATE_INTENT_SLUG|denial:${reason.code}",
            ),
        )
    }

    private companion object {
        const val RESOURCE_TYPE: String = "clinical.problem"
        const val UPDATE_INTENT_SLUG: String = "intent:clinical.problem.update"
        const val RESOLVE_INTENT_SLUG: String = "intent:clinical.problem.resolve"
        const val REVOKE_INTENT_SLUG: String = "intent:clinical.problem.revoke"
    }
}
