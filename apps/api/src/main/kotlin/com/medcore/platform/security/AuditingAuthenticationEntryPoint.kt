package com.medcore.platform.security

import com.medcore.platform.audit.ActorType
import com.medcore.platform.audit.AuditAction
import com.medcore.platform.audit.AuditEventCommand
import com.medcore.platform.audit.AuditOutcome
import com.medcore.platform.audit.AuditWriter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint
import org.springframework.security.web.AuthenticationEntryPoint

// Wraps Spring Security's BearerTokenAuthenticationEntryPoint to emit
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
class AuditingAuthenticationEntryPoint(
    private val auditWriter: AuditWriter,
    private val delegate: AuthenticationEntryPoint = BearerTokenAuthenticationEntryPoint(),
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        if (hasBearerToken(request)) {
            auditWriter.write(
                AuditEventCommand(
                    action = AuditAction.IDENTITY_USER_LOGIN_FAILURE,
                    actorType = ActorType.USER,
                    actorId = null,
                    outcome = AuditOutcome.DENIED,
                    reason = REASON_INVALID_BEARER,
                ),
            )
        }
        delegate.commence(request, response, authException)
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
