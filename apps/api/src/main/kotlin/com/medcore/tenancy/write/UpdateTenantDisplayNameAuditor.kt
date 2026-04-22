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
 * [WriteAuditor] for the tenancy display-name update (Phase 3J.2).
 *
 * ### Success emission
 *
 * - `action = TENANCY_TENANT_UPDATED`
 * - `reason = intent:tenant.update_display_name`
 * - `resourceType = "tenant"`, `resourceId = <slug>` — slug is the
 *   stable caller-facing identifier for tenants; using it as
 *   `resourceId` in BOTH the success and denial rows means a
 *   compliance query `WHERE resource_type = 'tenant' AND resource_id
 *   = 'acme-medical'` returns every event touching that tenant
 *   across both paths.
 * - `tenantId = <uuid>` — populated on success because the handler
 *   has already loaded the entity.
 *
 * ### Denial emission
 *
 * - `action = AUTHZ_WRITE_DENIED`
 * - `reason = intent:tenant.update_display_name|denial:<code>` —
 *   preserves the intent so compliance reviewers can separate
 *   denials by command, and carries the specific denial code from
 *   [WriteDenialReason] (not the coarse "NOT_A_MEMBER" catch-all
 *   that the pre-3J.2 resolver emitted).
 * - `resourceType = "tenant"`, `resourceId = <slug>` — slug is
 *   known at denial time even when the tenant UUID is not
 *   (unknown slug case). Preserves traceability for attempted
 *   attacks against specific slugs.
 * - `tenantId = null` — we do NOT resolve the tenant UUID on the
 *   denial path; the policy may have denied precisely because
 *   the tenant does not exist. Fetching it would leak existence
 *   via side-channel timing.
 *
 * ### No-op suppression
 *
 * When the handler returns `TenantSnapshot.changed = false`, the
 * success auditor emits nothing. This preserves "every persisted
 * change emits an audit row" — a no-op persists no change, so no
 * audit event is needed. See `TenantSnapshot` KDoc for the
 * compliance rationale.
 */
@Component
class UpdateTenantDisplayNameAuditor(
    private val auditWriter: AuditWriter,
) : WriteAuditor<UpdateTenantDisplayNameCommand, TenantSnapshot> {

    override fun onSuccess(
        command: UpdateTenantDisplayNameCommand,
        result: TenantSnapshot,
        context: WriteContext,
    ) {
        if (!result.changed) return
        auditWriter.write(
            AuditEventCommand(
                action = AuditAction.TENANCY_TENANT_UPDATED,
                actorType = ActorType.USER,
                actorId = context.principal.userId,
                tenantId = result.id,
                resourceType = RESOURCE_TYPE,
                resourceId = result.slug,
                outcome = AuditOutcome.SUCCESS,
                reason = INTENT_SLUG,
            ),
        )
    }

    override fun onDenied(
        command: UpdateTenantDisplayNameCommand,
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
                resourceId = command.slug,
                outcome = AuditOutcome.DENIED,
                reason = "$INTENT_SLUG|denial:${reason.code}",
            ),
        )
    }

    private companion object {
        const val RESOURCE_TYPE: String = "tenant"
        const val INTENT_SLUG: String = "intent:tenant.update_display_name"
    }
}
