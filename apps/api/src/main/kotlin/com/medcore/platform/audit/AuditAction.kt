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
