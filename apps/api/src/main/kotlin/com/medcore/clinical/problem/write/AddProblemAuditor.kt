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
 * [WriteAuditor] for problem creation (Phase 4E.2).
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Success — inside the WriteGate tx:**
 *   - action        = CLINICAL_PROBLEM_ADDED
 *   - actor_type    = USER
 *   - actor_id      = caller userId
 *   - tenant_id     = newly-recorded problem's tenantId
 *   - resource_type = "clinical.problem"
 *   - resource_id   = newly-minted problem UUID
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.problem.add"
 *
 * **Denial — outside tx:**
 *   - action        = AUTHZ_WRITE_DENIED
 *   - resource_type = "clinical.problem"
 *   - resource_id   = command.patientId.toString() (URL-path id;
 *                     problem id doesn't exist yet)
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.problem.add|denial:<code>"
 *
 * ### Why no severity token in the success reason
 *
 * 4E.1's allergy-add reason carries `|severity:<CLOSED_ENUM>`.
 * That's only safe for allergies because severity is required
 * (a closed-enum value is always available). Problems have
 * NULLABLE severity per locked Q3, so the analogue would either
 * (a) introduce a sentinel like `severity:UNSPECIFIED` (muddies
 * compliance queries that filter on real severity values), or
 * (b) omit the token sometimes (breaks the "stable shape"
 * contract for audit slugs). We deliberately drop the token
 * and rely on `resource_id` → `clinical.problem` lookup for
 * forensic narrowing on severity.
 *
 * ### PHI discipline
 *
 * Reason carries ONLY the closed intent token. Condition text
 * is PHI (combined with patient identity via the row's
 * patient_id) and NEVER appears in the audit row.
 *
 * ### No-op suppression — N/A
 *
 * Add is always a persisted INSERT; the success path always
 * emits exactly one audit row.
 */
@Component
class AddProblemAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<AddProblemCommand, ProblemSnapshot> {

    override fun onSuccess(
        command: AddProblemCommand,
        result: ProblemSnapshot,
        context: WriteContext,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_PROBLEM_ADDED,
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
        command: AddProblemCommand,
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
                resourceId = command.patientId.toString(),
                outcome = AuditOutcome.DENIED,
                reason = "$INTENT_SLUG|denial:${reason.code}",
            ),
        )
    }

    private companion object {
        const val RESOURCE_TYPE: String = "clinical.problem"
        const val INTENT_SLUG: String = "intent:clinical.problem.add"
    }
}
