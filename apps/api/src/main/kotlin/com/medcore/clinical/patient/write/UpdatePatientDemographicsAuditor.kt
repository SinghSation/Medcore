package com.medcore.clinical.patient.write

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
 * [WriteAuditor] for patient demographic updates (Phase 4A.2).
 *
 * ## Audit-row shape contract (NORMATIVE)
 *
 * **Success row:**
 *   - `action` = `PATIENT_DEMOGRAPHICS_UPDATED`
 *     (`clinical.patient.demographics_updated`)
 *   - `actor_type` = `USER`
 *   - `actor_id` = caller userId
 *   - `tenant_id` = target tenant UUID
 *   - `resource_type` = `"clinical.patient"`
 *   - `resource_id` = target patient UUID
 *   - `outcome` = `SUCCESS`
 *   - `reason` = `intent:clinical.patient.update_demographics|fields:<csv>`
 *
 *   `<csv>` is the comma-separated list of camelCase field names
 *   that changed — derived from [UpdatePatientDemographicsCommand.changingFieldNames].
 *   Order matches declaration in the command (stable, not
 *   alphabetised, so forensic consumers can rely on it).
 *
 * **Denial row:**
 *   - `action` = `AUTHZ_WRITE_DENIED`
 *   - `actor_id` = caller userId
 *   - `tenant_id` = NULL
 *   - `resource_type` = `"clinical.patient"`
 *   - `resource_id` = command.patientId.toString() (the path
 *     variable carries the target id regardless of denial)
 *   - `outcome` = `DENIED`
 *   - `reason` = `intent:clinical.patient.update_demographics|denial:<code>`
 *
 * ## PHI discipline — `fields` is names only
 *
 * The `fields` slug carries ONLY camelCase field names (closed set
 * from the DTO: `nameGiven`, `nameFamily`, `birthDate`, etc.). It
 * does NOT carry new or old values. Before/after diffing in the
 * audit row is a Phase 7 audit-schema-evolution concern, tracked
 * as a carry-forward.
 *
 * ## No-op suppression
 *
 * When [UpdatePatientDemographicsSnapshot.changed] is `false`
 * (every field's Set/Clear resolved to the existing value), the
 * auditor emits nothing. "Every persisted change emits an audit
 * row" — no-ops persist no change.
 */
@Component
class UpdatePatientDemographicsAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<UpdatePatientDemographicsCommand, UpdatePatientDemographicsSnapshot> {

    override fun onSuccess(
        command: UpdatePatientDemographicsCommand,
        result: UpdatePatientDemographicsSnapshot,
        context: WriteContext,
    ) {
        if (!result.changed) return
        val fields = result.changedFields.joinToString(",")
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.PATIENT_DEMOGRAPHICS_UPDATED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = result.snapshot.tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = result.snapshot.id.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = "$INTENT_SLUG|fields:$fields",
            ),
        )
    }

    override fun onDenied(
        command: UpdatePatientDemographicsCommand,
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
        const val RESOURCE_TYPE: String = "clinical.patient"
        const val INTENT_SLUG: String = "intent:clinical.patient.update_demographics"
    }
}
