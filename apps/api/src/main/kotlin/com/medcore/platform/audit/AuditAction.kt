package com.medcore.platform.audit

/**
 * Closed set of audit actions emitted by Medcore.
 *
 * The wire value ([code]) is what is persisted into `audit.audit_event.action`
 * and is part of the stable audit contract — it MUST NOT change for an
 * existing action once emitted in any environment.
 *
 * **Registry discipline** (mirrors the [com.medcore.platform.api.ErrorCodes]
 * rule from ADR-005 §2.3 for wire-facing stable identifiers). Adding a
 * new action requires, in the same slice:
 *   1. A new enum entry here with a stable dotted `code`.
 *   2. At least one test that asserts emission of the new action in
 *      the scenario that triggers it.
 *   3. A review-pack callout that the audit-action set is expanding.
 *   4. A runbook or DoD-row mention of when / why the action fires.
 *
 * Renaming or removing a code is a breaking audit-contract change —
 * handle via superseding ADR, never in-place.
 */
enum class AuditAction(val code: String) {
    // --- Phase 3C: identity + tenancy (ADR-003 §7) ---
    IDENTITY_USER_PROVISIONED("identity.user.provisioned"),
    IDENTITY_USER_LOGIN_SUCCESS("identity.user.login.success"),
    IDENTITY_USER_LOGIN_FAILURE("identity.user.login.failure"),
    TENANCY_CONTEXT_SET("tenancy.context.set"),
    TENANCY_MEMBERSHIP_LIST("tenancy.membership.list"),
    TENANCY_MEMBERSHIP_DENIED("tenancy.membership.denied"),

    // --- Phase 3F.4: audit chain integrity verification ---
    /**
     * Emitted exactly once per scheduled verification cycle when the
     * chain walk reports one or more breaks. Distinguishes chain
     * integrity failure (the audit chain itself is compromised) from
     * verifier infrastructure failure (the verifier could not run).
     * Reason slug format: `breaks:<N>|reason:<first-reason-code>`
     * where `<first-reason-code>` is one of the closed set emitted
     * by `audit.verify_chain()` (V9).
     */
    AUDIT_CHAIN_INTEGRITY_FAILED("audit.chain.integrity_failed"),

    /**
     * Emitted exactly once per scheduled verification cycle when the
     * verifier itself could not complete (DB unreachable, function
     * missing, permission denied, etc.). Separate from
     * [AUDIT_CHAIN_INTEGRITY_FAILED] so a compliance reviewer can
     * distinguish "chain is broken" (a clinical-safety incident)
     * from "we could not check the chain" (an infrastructure
     * incident). Reason slug format: `verifier_failed` — no
     * exception detail (Rule 01).
     */
    AUDIT_CHAIN_VERIFICATION_FAILED("audit.chain.verification_failed"),

    // --- Phase 3J: write-gate authorization denial ---
    /**
     * Emitted by every [com.medcore.platform.write.WriteAuditor]
     * implementation when [com.medcore.platform.write.AuthzPolicy.check]
     * refuses a mutation. `reason` carries the
     * [com.medcore.platform.write.WriteDenialReason.code] slug;
     * `resource_type` / `resource_id` identify what the mutation
     * targeted; no command payload leaks. Closes the 3G
     * carry-forward "audit emission on 403 access-denied."
     */
    AUTHZ_WRITE_DENIED("authz.write.denied"),

    // --- Phase 3J.2: tenancy write success ---
    /**
     * Emitted by a [com.medcore.platform.write.WriteAuditor] on the
     * success path of any `tenancy.tenant` mutation. Phase 3J.2
     * ships the first consumer: display-name updates via
     * `PATCH /api/v1/tenants/{slug}`. `reason` carries the
     * per-command intent slug (e.g., `intent:tenant.update_display_name`)
     * so sibling mutations that share this coarse action can be
     * distinguished without a schema change (ADR-007 §2.4).
     * Suppressed for no-op writes — the handler returns
     * `TenantSnapshot.changed = false`, the auditor skips emission,
     * "every persisted change emits an audit row" holds because
     * no-ops persist nothing.
     */
    TENANCY_TENANT_UPDATED("tenancy.tenant.updated"),

    // --- Phase 3J.3: membership invite ---
    /**
     * Emitted on the SUCCESS path of
     * `POST /api/v1/tenants/{slug}/memberships` when an
     * OWNER/ADMIN creates an ACTIVE membership for an existing
     * user (Phase 3J.3, ADR-007 §4.9).
     *
     * Normative audit-row shape contract is defined on
     * [com.medcore.tenancy.write.InviteTenantMembershipAuditor] —
     * successes and denials are queried by the
     * (`resource_type`, `outcome`) pair, NOT by `resource_id`
     * alone (the `resource_id` column carries different values
     * across outcome by design; see that KDoc for the canonical
     * shape + query examples).
     *
     * Naming: "invited" matches the `MEMBERSHIP_INVITE` authority
     * semantics. Phase 3J.3 is direct ACTIVE creation — email-token
     * invitation flows (PENDING lifecycle) land in a later slice
     * with their own action.
     */
    TENANCY_MEMBERSHIP_INVITED("tenancy.membership.invited"),
}

/**
 * Who / what initiated the audited action. Matches the CHECK constraint
 * on `audit.audit_event.actor_type` in V7.
 */
enum class ActorType {
    USER,
    SYSTEM,
    SERVICE,
}

/**
 * Terminal state of the audited action. Matches the CHECK constraint on
 * `audit.audit_event.outcome` in V7.
 */
enum class AuditOutcome {
    SUCCESS,
    DENIED,
    ERROR,
}
