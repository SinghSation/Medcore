package com.medcore.platform.security

import java.util.UUID
import org.springframework.security.authentication.DisabledException

/**
 * Raised by [PrincipalResolver] when an inbound token authenticates
 * cryptographically but maps to an `identity.user` row whose status
 * is not [PrincipalStatus.ACTIVE] (Phase 3K.1, ADR-008 §2.6).
 *
 * ### Why this exists
 *
 * ADR-008 locks the invariant: **Medcore's `PrincipalStatus` is
 * authoritative at every authenticated request.** A cryptographically-
 * valid token for a user Medcore has marked DISABLED or DELETED MUST
 * be rejected at the authentication boundary, even though the
 * broker's token is otherwise valid.
 *
 * The broker (WorkOS) and Medcore are **not synchronised**
 * bi-directionally in real time. IdP-side deactivation propagates via
 * token expiry (the broker stops issuing tokens for the disabled
 * user), but existing un-expired tokens must be revocable at the
 * Medcore layer — the broker is an orchestration layer, not the
 * system of record.
 *
 * ### Why a subclass of [DisabledException]
 *
 * [DisabledException] is Spring Security's canonical
 * "authentication refused because the account is disabled" exception.
 * It extends [org.springframework.security.core.AuthenticationException],
 * so Spring Security's exception-translation pipeline routes it to
 * the configured [org.springframework.security.web.AuthenticationEntryPoint]
 * (Medcore's [AuditingAuthenticationEntryPoint]) as a 401 — the
 * correct semantic ("not authenticated" rather than "not authorized").
 *
 * The subclass carries the [actorId] + closed-enum [reasonCode] so
 * the entry point can emit an `IDENTITY_USER_LOGIN_FAILURE` audit
 * row that identifies the specific user — the generic
 * `invalid_bearer_token` reason would lose forensic value here
 * (a terminated employee's failed access attempt is exactly what
 * compliance wants to see in the log).
 *
 * ### No user-supplied text in [reasonCode]
 *
 * [reasonCode] is one of a small closed set of slugs
 * (`principal_disabled`, `principal_deleted`). The audit row emits
 * this slug directly; no free-form message crosses the audit
 * boundary (Rule 01, ADR-003 §3).
 */
class PrincipalStatusDeniedException(
    val actorId: UUID,
    val reasonCode: String,
) : DisabledException(
    "principal status denied: actorId=$actorId reason=$reasonCode",
) {
    companion object {
        const val REASON_DISABLED: String = "principal_disabled"
        const val REASON_DELETED: String = "principal_deleted"
    }
}
