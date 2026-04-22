package com.medcore.platform.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.medcore.platform.api.ErrorCodes
import com.medcore.platform.api.ErrorResponses
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint

/**
 * Writes the unified Medcore 401 envelope for every authentication
 * failure at the OAuth2 resource-server boundary.
 *
 * Replaces Spring's `BearerTokenAuthenticationEntryPoint` as the
 * terminal 401 emitter. Spring's version writes a Spring-shaped body
 * and relies on `WWW-Authenticate` headers for clients; Medcore wants
 * a single canonical envelope across every 401 scenario.
 *
 * Emission rules:
 *   - Always sets status 401 and content type `application/json`.
 *   - Body is [com.medcore.platform.api.ErrorResponse] with code
 *     [ErrorCodes.AUTH_UNAUTHENTICATED] and a single non-enumerating
 *     message `"Authentication required."` regardless of the
 *     underlying cause (no token / invalid token / expired token /
 *     bad issuer / bad audience — all return the same body).
 *   - Never echoes the `AuthenticationException.message`, the token,
 *     the JWT claims, or any framework class name.
 *   - `requestId` populated from MDC via [ErrorResponses.of].
 *
 * Audit emission is NOT this class's concern — the outer
 * [AuditingAuthenticationEntryPoint] wraps this one and emits the
 * `identity.user.login.failure` audit row when applicable, then
 * delegates here.
 */
class MedcoreAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        // RFC 6750 §3: a 401 from a Bearer-token resource MUST carry
        // `WWW-Authenticate: Bearer`. Spring's default
        // BearerTokenAuthenticationEntryPoint emits a richer variant
        // (with `realm=` / `error=` / `error_description=`), but those
        // fields can echo exception detail to clients — we keep the
        // minimal `Bearer` token to signal the scheme without leaking
        // anything about WHY authentication failed (Rule 01 §enumeration).
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.setHeader(WWW_AUTHENTICATE_HEADER, BEARER_CHALLENGE)
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(
            response.outputStream,
            ErrorResponses.of(
                code = ErrorCodes.AUTH_UNAUTHENTICATED,
                message = MESSAGE,
            ),
        )
    }

    companion object {
        const val MESSAGE: String = "Authentication required."
        const val WWW_AUTHENTICATE_HEADER: String = "WWW-Authenticate"
        const val BEARER_CHALLENGE: String = "Bearer"
    }
}
