package com.medcore.platform.security

import com.medcore.platform.audit.ActorType
import com.medcore.platform.audit.AuditAction
import com.medcore.platform.audit.AuditEventCommand
import com.medcore.platform.audit.AuditOutcome
import com.medcore.platform.audit.AuditWriter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint

// Wraps the terminal AuthenticationEntryPoint to emit
// identity.user.login.failure at the resource-server authentication
// boundary (ADR-003 §7). Invalid JWTs never reach
// IdentityProvisioningService, so this is the only correct site.
//
// Emission rule:
//   - Fires ONLY when the inbound request presented an
//     "Authorization: Bearer ..." header. Anonymous requests to
//     /api/** still return 401 but are NOT audited — otherwise every
//     unauthed probe generates noise indistinguishable from a real
//     authentication attempt.
//   - actor_id is null — by definition we failed before we could map
//     the token to an internal user.
//   - reason carries a short, stable code ("invalid_bearer_token") —
//     never the raw exception message, never any JWT content (Rule 01).
//
// Failure of the audit write propagates; the delegate is invoked only
// after the audit row is committed. ADR-003 §2: a failed audit fails
// the audited action.
//
// Phase 3G: the default delegate is the Medcore-shaped entry point,
// which writes the unified ErrorResponse envelope to the response
// body. Spring's BearerTokenAuthenticationEntryPoint is no longer
// used — every 401 caller sees the same envelope regardless of how
// the authentication failure was raised.
class AuditingAuthenticationEntryPoint(
    private val auditWriter: AuditWriter,
    private val delegate: AuthenticationEntryPoint,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        if (hasBearerToken(request)) {
            // Phase 3K.1 (ADR-008 §2.6): distinguish the
            // PrincipalStatusDeniedException path (Medcore rejected
            // a cryptographically-valid token because the mapped
            // user is DISABLED / DELETED) from the invalid-bearer
            // path. The former carries the actorId for forensics;
            // the latter is null because the token never resolved
            // to an internal user.
            val (actorId, reason) = when (val cause = findPrincipalStatusDenial(authException)) {
                null -> null to REASON_INVALID_BEARER
                else -> cause.actorId to cause.reasonCode
            }
            auditWriter.write(
                AuditEventCommand(
                    action = AuditAction.IDENTITY_USER_LOGIN_FAILURE,
                    actorType = ActorType.USER,
                    actorId = actorId,
                    outcome = AuditOutcome.DENIED,
                    reason = reason,
                ),
            )
        }
        delegate.commence(request, response, authException)
    }

    /**
     * Unwraps the exception chain looking for a
     * [PrincipalStatusDeniedException]. Spring Security's resource-
     * server pipeline may re-wrap auth exceptions; walking the
     * cause chain handles both direct propagation and wrapped
     * cases consistently.
     */
    private fun findPrincipalStatusDenial(
        ex: Throwable,
    ): PrincipalStatusDeniedException? {
        var current: Throwable? = ex
        while (current != null) {
            if (current is PrincipalStatusDeniedException) return current
            if (current.cause === current) return null
            current = current.cause
        }
        return null
    }

    private fun hasBearerToken(request: HttpServletRequest): Boolean {
        val header = request.getHeader(AUTHORIZATION_HEADER) ?: return false
        return header.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)
    }

    companion object {
        const val AUTHORIZATION_HEADER: String = "Authorization"
        const val BEARER_PREFIX: String = "Bearer "
        const val REASON_INVALID_BEARER: String = "invalid_bearer_token"
    }
}
