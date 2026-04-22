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
 * [WriteAuditor] for role update (Phase 3J.N).
 *
 * ## Audit-row shape contract (NORMATIVE, extends 3J.3)
 *
 * Both success and denial rows use
 * `resource_type = "tenant_membership"` and
 * `resource_id = <target membership UUID>` — the membership ALWAYS
 * exists for role-update (URL path carries a concrete id). This
 * breaks the 3J.3 denial-row asymmetry (where the target was a
 * user UUID because no membership existed yet) BY DESIGN: for
 * 3J.N operations on existing memberships, the membership UUID is
 * the natural stable key on both paths.
 *
 * Queries:
 *
 * ```sql
 * -- All role changes against a specific membership (history):
 * SELECT * FROM audit.audit_event
 *  WHERE resource_type = 'tenant_membership'
 *    AND resource_id = :membershipId
 *    AND reason LIKE 'intent:tenancy.membership.update_role|%';
 *
 * -- All denied role-change attempts in a tenant:
 * SELECT * FROM audit.audit_event
 *  WHERE action = 'authz.write.denied'
 *    AND outcome = 'DENIED'
 *    AND reason LIKE 'intent:tenancy.membership.update_role|denial:%'
 *    AND resource_type = 'tenant_membership';
 * ```
 *
 * Success `reason` encodes `from:<X>|to:<Y>` so forensics can
 * reconstruct the transition without joining historical membership
 * rows. Closed-enum values (OWNER | ADMIN | MEMBER) — bounded,
 * parseable, no PHI risk. Promotion to a structured audit-payload
 * diff column is deferred to the Phase 7 audit-schema-evolution
 * ADR.
 *
 * ### No-op suppression
 *
 * When `RoleUpdateSnapshot.changed = false` (PATCH to same role),
 * the auditor emits nothing. "Every persisted change emits an
 * audit row" holds because no-ops persist no change.
 */
@Component
class UpdateTenantMembershipRoleAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<UpdateTenantMembershipRoleCommand, RoleUpdateSnapshot> {

    override fun onSuccess(
        command: UpdateTenantMembershipRoleCommand,
        result: RoleUpdateSnapshot,
        context: WriteContext,
    ) {
        if (!result.changed) return
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.TENANCY_MEMBERSHIP_ROLE_UPDATED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = result.snapshot.tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = result.snapshot.id.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = "$INTENT_SLUG|from:${result.priorRole}|to:${result.snapshot.role}",
            ),
        )
    }

    override fun onDenied(
        command: UpdateTenantMembershipRoleCommand,
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
        const val INTENT_SLUG: String = "intent:tenancy.membership.update_role"
    }
}
