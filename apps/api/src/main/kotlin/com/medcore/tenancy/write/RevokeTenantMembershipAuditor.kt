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
 * [WriteAuditor] for membership revocation (Phase 3J.N).
 *
 * Follows the extended 3J.N audit-shape contract (see
 * [UpdateTenantMembershipRoleAuditor] KDoc): both success and
 * denial rows use `resource_type = "tenant_membership"` and
 * `resource_id = <target membership UUID>`. The target always
 * exists (URL path carries the id), so the membership UUID is
 * the natural key on both paths.
 *
 * Success `reason` encodes `prior_role:<X>` so forensic
 * reconstruction does not require joining the pre-revoke state
 * from elsewhere. Idempotent retries on already-REVOKED
 * memberships suppress emission (handler returns
 * `changed = false`).
 *
 * Canonical queries:
 *
 * ```sql
 * -- Every OWNER revocation in a tenant:
 * SELECT * FROM audit.audit_event
 *  WHERE action = 'tenancy.membership.revoked'
 *    AND tenant_id = :tenantId
 *    AND reason LIKE 'intent:tenancy.membership.remove|prior_role:OWNER%';
 *
 * -- Every denied revocation against a specific membership:
 * SELECT * FROM audit.audit_event
 *  WHERE action = 'authz.write.denied'
 *    AND outcome = 'DENIED'
 *    AND resource_type = 'tenant_membership'
 *    AND resource_id = :membershipId::text
 *    AND reason LIKE 'intent:tenancy.membership.remove|%';
 * ```
 */
@Component
class RevokeTenantMembershipAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<RevokeTenantMembershipCommand, RevokeSnapshot> {

    override fun onSuccess(
        command: RevokeTenantMembershipCommand,
        result: RevokeSnapshot,
        context: WriteContext,
    ) {
        if (!result.changed) return
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.TENANCY_MEMBERSHIP_REVOKED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = result.snapshot.tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = result.snapshot.id.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = "$INTENT_SLUG|prior_role:${result.priorRole}",
            ),
        )
    }

    override fun onDenied(
        command: RevokeTenantMembershipCommand,
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
                resourceId = command.membershipId.toString(),
                outcome = AuditOutcome.DENIED,
                reason = "$INTENT_SLUG|denial:${reason.code}",
            ),
        )
    }

    private companion object {
        const val RESOURCE_TYPE: String = "tenant_membership"
        const val INTENT_SLUG: String = "intent:tenancy.membership.remove"
    }
}
