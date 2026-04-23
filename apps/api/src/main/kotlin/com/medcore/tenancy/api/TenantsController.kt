package com.medcore.tenancy.api

import com.medcore.platform.observability.MdcKeys
import com.medcore.platform.security.MedcorePrincipal
import com.medcore.platform.tenancy.MembershipStatus
import com.medcore.platform.tenancy.TenantStatus
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteGate
import com.medcore.platform.api.ApiResponse
import com.medcore.tenancy.service.MembershipDetail
import com.medcore.tenancy.service.TenancyService
import com.medcore.tenancy.service.TenantMembershipResult
import com.medcore.tenancy.write.InviteTenantMembershipCommand
import com.medcore.tenancy.write.InviteTenantMembershipHandler
import com.medcore.tenancy.write.MembershipSnapshot
import com.medcore.tenancy.write.RevokeSnapshot
import com.medcore.tenancy.write.RevokeTenantMembershipCommand
import com.medcore.tenancy.write.RevokeTenantMembershipHandler
import com.medcore.tenancy.write.RoleUpdateSnapshot
import com.medcore.tenancy.write.TenantSnapshot
import com.medcore.tenancy.write.UpdateTenantDisplayNameCommand
import com.medcore.tenancy.write.UpdateTenantDisplayNameHandler
import com.medcore.tenancy.write.UpdateTenantMembershipRoleCommand
import com.medcore.tenancy.write.UpdateTenantMembershipRoleHandler
import jakarta.validation.Valid
import java.util.UUID
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Contract: packages/schemas/openapi/tenancy/tenancy.yaml.
//
// Both endpoints require authentication (Spring Security's filter chain on
// /api/** is configured in platform.security.SecurityConfig). Neither
// endpoint currently requires the X-Medcore-Tenant header — it is OPTIONAL
// in this slice; tenancy.context.TenantContextFilter validates it when
// present so future routes can adopt it without reinventing the wheel.
//
// Audit emission lives in the tenancy service, not here (Phase 3C scope
// rule: "never emit audit from controllers"). The controller only maps
// the service's sealed TenantMembershipResult into an HTTP response —
// the denial audit row has already been written by the time the
// exception is thrown.
@RestController
@RequestMapping("/api/v1/tenants")
class TenantsController(
    private val tenancyService: TenancyService,
    private val updateTenantDisplayNameGate: WriteGate<UpdateTenantDisplayNameCommand, TenantSnapshot>,
    private val updateTenantDisplayNameHandler: UpdateTenantDisplayNameHandler,
    private val inviteTenantMembershipGate: WriteGate<InviteTenantMembershipCommand, MembershipSnapshot>,
    private val inviteTenantMembershipHandler: InviteTenantMembershipHandler,
    private val updateTenantMembershipRoleGate: WriteGate<UpdateTenantMembershipRoleCommand, RoleUpdateSnapshot>,
    private val updateTenantMembershipRoleHandler: UpdateTenantMembershipRoleHandler,
    private val revokeTenantMembershipGate: WriteGate<RevokeTenantMembershipCommand, RevokeSnapshot>,
    private val revokeTenantMembershipHandler: RevokeTenantMembershipHandler,
) {

    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(@AuthenticationPrincipal principal: MedcorePrincipal): MembershipListResponse {
        val memberships = tenancyService
            .listMembershipsFor(principal.userId)
            .filter { it.isVisibleOnList() }
            .map { it.toResponse() }
        return MembershipListResponse(items = memberships)
    }

    @GetMapping("/{slug}/me", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun membershipForCurrentUser(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
    ): MembershipResponse = when (
        val result = tenancyService.findMembershipForCallerBySlug(principal.userId, slug)
    ) {
        is TenantMembershipResult.Granted -> result.detail.toResponse()
        is TenantMembershipResult.Denied -> throw TenantAccessDeniedException(
            "denied userId=${principal.userId} slug=$slug reason=${result.reason.code}",
        )
    }

    /**
     * First concrete write through the Phase 3J.1 WriteGate substrate
     * (Phase 3J.2, ADR-007 §4). Display-name updates only — status
     * changes, slug changes, and deletion remain out of scope until a
     * subsequent slice.
     *
     * Flow: `@Valid` → `UpdateTenantDisplayNameValidator` →
     * `UpdateTenantDisplayNamePolicy` (via `WriteGate`) →
     * `UpdateTenantDisplayNameHandler` (inside the gate's transaction)
     * → `UpdateTenantDisplayNameAuditor.onSuccess` (same transaction) →
     * response. Denial fires `AUTHZ_WRITE_DENIED` via the auditor's
     * `onDenied` path and re-throws; Phase 3G maps the
     * `WriteAuthorizationException` to `403 auth.forbidden`.
     */
    @PatchMapping(
        "/{slug}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun updateTenant(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @Valid @RequestBody body: UpdateTenantRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ApiResponse<TenantSummaryResponse> {
        val command = UpdateTenantDisplayNameCommand(
            slug = slug,
            // `!!` codifies the @Valid @NotBlank guarantee: if we reach
            // this line, Spring has already rejected null / empty bodies
            // with 422. Trimming here normalises the value before the
            // validator's post-trim-emptiness + control-chars checks.
            displayName = body.displayName!!.trim(),
        )
        val context = WriteContext(
            principal = principal,
            idempotencyKey = idempotencyKey,
        )
        val snapshot = updateTenantDisplayNameGate.apply(command, context) { cmd ->
            updateTenantDisplayNameHandler.handle(cmd)
        }
        return ApiResponse(
            data = TenantSummaryResponse.from(snapshot),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
    }

    /**
     * Membership invite (Phase 3J.3, ADR-007 §4.9). Second concrete
     * write through the WriteGate substrate. OWNER / ADMIN creates
     * an ACTIVE membership for an already-provisioned user;
     * ADMIN-invites-OWNER is forbidden (policy guard).
     *
     * **`Idempotency-Key` header is currently shape-only.** Accepted
     * and propagated into `WriteContext.idempotencyKey` but NOT
     * used to dedupe retries. Duplicate submissions produce the
     * standard 409 via the V6 `uq_tenancy_membership_tenant_user`
     * constraint. True idempotent-retry semantics arrive alongside
     * Phase 4A's patient-create flow; client implementations should
     * NOT assume retry-safe behaviour on this endpoint yet.
     */
    @PostMapping(
        "/{slug}/memberships",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun inviteMembership(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @Valid @RequestBody body: InviteMembershipRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<ApiResponse<MembershipResponse>> {
        val command = InviteTenantMembershipCommand(
            slug = slug,
            // `!!` codifies the @Valid @NotNull guarantee. If this
            // line runs, Spring has already rejected null bodies
            // with 422 via MethodArgumentNotValidException.
            userId = body.userId!!,
            role = body.role!!,
        )
        val context = WriteContext(
            principal = principal,
            idempotencyKey = idempotencyKey,
        )
        val snapshot = inviteTenantMembershipGate.apply(command, context) { cmd ->
            inviteTenantMembershipHandler.handle(cmd)
        }
        val responseBody = ApiResponse(
            data = MembershipResponse.from(snapshot),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(responseBody)
    }

    /**
     * Role update (Phase 3J.N). Requires
     * `MEMBERSHIP_ROLE_UPDATE` + target-OWNER guard (ADMIN cannot
     * modify an OWNER) + escalation guard (only OWNER can promote
     * to OWNER) + last-OWNER invariant (cannot demote the last
     * active OWNER; enforced in the handler with a pessimistic
     * row lock so concurrent demotions serialise).
     *
     * `Idempotency-Key` header remains shape-only (same status as
     * 3J.3 — not deduped). Same-role PATCHes are no-ops: 200 with
     * no audit row and no row_version bump.
     */
    @PatchMapping(
        "/{slug}/memberships/{membershipId}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun updateMembershipRole(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable membershipId: UUID,
        @Valid @RequestBody body: UpdateMembershipRoleRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ApiResponse<MembershipResponse> {
        val command = UpdateTenantMembershipRoleCommand(
            slug = slug,
            membershipId = membershipId,
            newRole = body.role!!,
        )
        val context = WriteContext(
            principal = principal,
            idempotencyKey = idempotencyKey,
        )
        val result = updateTenantMembershipRoleGate.apply(command, context) { cmd ->
            updateTenantMembershipRoleHandler.handle(cmd, context)
        }
        return ApiResponse(
            data = MembershipResponse.from(result.snapshot),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
    }

    /**
     * Membership revocation (Phase 3J.N). Soft-delete via
     * status transition ACTIVE|SUSPENDED → REVOKED. Returns 204
     * with no body; `X-Request-Id` header carries the correlation.
     * Idempotent: DELETE-to-already-REVOKED returns 204 with no
     * state change and no audit row. Last-OWNER invariant applies
     * (cannot revoke the last active OWNER).
     */
    @DeleteMapping("/{slug}/memberships/{membershipId}")
    fun revokeMembership(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable membershipId: UUID,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<Void> {
        val command = RevokeTenantMembershipCommand(
            slug = slug,
            membershipId = membershipId,
        )
        val context = WriteContext(
            principal = principal,
            idempotencyKey = idempotencyKey,
        )
        revokeTenantMembershipGate.apply(command, context) { cmd ->
            revokeTenantMembershipHandler.handle(cmd, context)
        }
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    private fun MembershipDetail.isVisibleOnList(): Boolean =
        status == MembershipStatus.ACTIVE && tenant.status == TenantStatus.ACTIVE

    private fun MembershipDetail.toResponse(): MembershipResponse = MembershipResponse(
        membershipId = membershipId.toString(),
        userId = userId.toString(),
        role = role,
        status = status,
        tenant = TenantSummaryResponse(
            id = tenant.id.toString(),
            slug = tenant.slug,
            displayName = tenant.displayName,
            status = tenant.status,
        ),
    )
}
