package com.medcore.tenancy.write

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
 * [WriteAuditor] for membership invite (Phase 3J.3).
 *
 * ## Audit-row shape contract (NORMATIVE)
 *
 * The `resource_id` column carries DIFFERENT values depending on
 * the row's `outcome`. This asymmetry is deliberate (see rationale
 * below) and MUST be preserved by any future code path producing
 * membership-invite audit rows. Downstream analytics / audit
 * tooling query by the (`resource_type`, `outcome`) pair, never by
 * `resource_id` alone.
 *
 * ```
 * For action = 'tenancy.membership.invited' (SUCCESS path):
 *     resource_type = 'tenant_membership'
 *     resource_id   = <new membership UUID>
 *     tenant_id     = <tenant UUID>       -- populated
 *     outcome       = 'SUCCESS'
 *     reason        = 'intent:tenancy.membership.invite'
 *
 * For action = 'authz.write.denied' with
 *     reason LIKE 'intent:tenancy.membership.invite|denial:%'
 *     (DENIED path):
 *     resource_type = 'tenant_membership'
 *     resource_id   = <target user UUID>  -- NOT a membership UUID
 *     tenant_id     = NULL                -- enumeration protection
 *     outcome       = 'DENIED'
 *     reason        = 'intent:tenancy.membership.invite|denial:<code>'
 * ```
 *
 * ### Canonical query shapes
 *
 * ```sql
 * -- All successful invites against a tenant:
 * SELECT * FROM audit.audit_event
 *  WHERE action = 'tenancy.membership.invited'
 *    AND outcome = 'SUCCESS'
 *    AND tenant_id = :tenantId;
 *
 * -- All denied invites against a specific user (enumeration / abuse):
 * SELECT * FROM audit.audit_event
 *  WHERE action = 'authz.write.denied'
 *    AND outcome = 'DENIED'
 *    AND resource_type = 'tenant_membership'
 *    AND resource_id = :targetUserId::text
 *    AND reason LIKE 'intent:tenancy.membership.invite|%';
 * ```
 *
 * ### Why the asymmetry
 *
 * A prospective (denied) membership has no UUID — the row doesn't
 * exist. Its natural identifying key is `(tenant_id, user_id)`. On
 * the denial path, `tenant_id` is deliberately null (populating it
 * would leak tenant existence to non-members). That leaves
 * `resource_id = target user UUID` as the only column-level
 * structured identifier, so compliance queries stay column-based
 * instead of parsing `reason` strings. See PHI review 3J.3 §2.6
 * and ADR-007 §4.9.
 *
 * Any future auditor facing the same constraint (denial-row with
 * null tenant_id and a compound natural key) should adopt the same
 * pattern for consistency.
 */
@Component
class InviteTenantMembershipAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<InviteTenantMembershipCommand, MembershipSnapshot> {

    override fun onSuccess(
        command: InviteTenantMembershipCommand,
        result: MembershipSnapshot,
        context: WriteContext,
    ) {
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.TENANCY_MEMBERSHIP_INVITED,
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
        command: InviteTenantMembershipCommand,
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
                resourceId = command.userId.toString(),
                outcome = AuditOutcome.DENIED,
                reason = "$INTENT_SLUG|denial:${reason.code}",
            ),
        )
    }

    private companion object {
        const val RESOURCE_TYPE: String = "tenant_membership"
        const val INTENT_SLUG: String = "intent:tenancy.membership.invite"
    }
}
