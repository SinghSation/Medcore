package com.medcore.platform.audit

/**
 * Closed set of audit actions emitted by Medcore in Phase 3C.
 *
 * The wire value ([code]) is what is persisted into `audit.audit_event.action`
 * and is part of the stable audit contract — it MUST NOT change for an
 * existing action. New values land via additive migration + ADR.
 *
 * Scope for 3C matches ADR-003 §2 + §7 exactly. Do not extend this enum
 * in this slice; future actions ship in their own phase.
 */
enum class AuditAction(val code: String) {
    IDENTITY_USER_PROVISIONED("identity.user.provisioned"),
    IDENTITY_USER_LOGIN_SUCCESS("identity.user.login.success"),
    IDENTITY_USER_LOGIN_FAILURE("identity.user.login.failure"),
    TENANCY_CONTEXT_SET("tenancy.context.set"),
    TENANCY_MEMBERSHIP_LIST("tenancy.membership.list"),
    TENANCY_MEMBERSHIP_DENIED("tenancy.membership.denied"),
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
