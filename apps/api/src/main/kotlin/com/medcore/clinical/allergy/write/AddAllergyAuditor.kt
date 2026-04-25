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
 * [WriteAuditor] for allergy creation (Phase 4E.1).
 *
 * ### Audit-row shape contract (NORMATIVE)
 *
 * **Success — inside the WriteGate tx:**
 *   - action        = CLINICAL_ALLERGY_ADDED
 *   - actor_type    = USER
 *   - actor_id      = caller userId
 *   - tenant_id     = newly-recorded allergy's tenantId
 *   - resource_type = "clinical.allergy"
 *   - resource_id   = newly-minted allergy UUID
 *   - outcome       = SUCCESS
 *   - reason        = "intent:clinical.allergy.add|severity:<CLOSED_ENUM>"
 *
 * **Denial — outside tx:**
 *   - action        = AUTHZ_WRITE_DENIED
 *   - resource_type = "clinical.allergy"
 *   - resource_id   = command.patientId.toString() (URL-path id;
 *                     allergy id doesn't exist yet)
 *   - outcome       = DENIED
 *   - reason        = "intent:clinical.allergy.add|denial:<code>"
 *
 * ### PHI discipline
 *
 * Reason carries ONLY the closed-enum severity token. Substance
 * text and reaction text are PHI (combined with patient identity
 * via the row's patient_id) and NEVER appear in the audit row.
 * Forensic linkage to the specific allergy goes through
 * `resource_id` → the RLS-gated SELECT path on
 * `clinical.allergy`.
 *
 * ### No-op suppression — N/A
 *
 * Add is always a persisted INSERT; the success path always
 * emits exactly one audit row.
 */
@Component
class AddAllergyAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<AddAllergyCommand, AllergySnapshot> {

    override fun onSuccess(
        command: AddAllergyCommand,
        result: AllergySnapshot,
        context: WriteContext,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.CLINICAL_ALLERGY_ADDED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = result.tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = result.id.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = "$INTENT_SLUG|severity:${result.severity.name}",
            ),
        )
    }

    override fun onDenied(
        command: AddAllergyCommand,
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
        const val RESOURCE_TYPE: String = "clinical.allergy"
        const val INTENT_SLUG: String = "intent:clinical.allergy.add"
    }
}
