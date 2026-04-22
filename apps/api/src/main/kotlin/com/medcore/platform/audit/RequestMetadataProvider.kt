package com.medcore.platform.audit

import com.medcore.platform.observability.MdcKeys
import com.medcore.platform.observability.ProxyAwareClientIpResolver
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * Reads request-scoped metadata for audit writes.
 *
 * Safe to call outside a request scope: returns [RequestMetadata.EMPTY]
 * when no `ServletRequestAttributes` are bound (e.g., background jobs).
 *
 * Field sources (Phase 3F.1):
 *
 * `request_id`:
 *   Lifted from MDC ([MdcKeys.REQUEST_ID]) populated by
 *   [com.medcore.platform.observability.RequestIdFilter]. Because the
 *   filter runs BEFORE Spring Security, the id is present for every
 *   audit write on the request thread — including login-failure
 *   audits emitted by the authentication entry point. The value in
 *   MDC is exactly the value echoed on the response header and
 *   persisted to `audit.audit_event.request_id`, which is the end-to-
 *   end correlation the phase-3F roadmap exit criterion requires.
 *
 *   Closes the 3C carry-forward for a central request-ID generator.
 *
 * `client_ip`:
 *   Resolved through [ProxyAwareClientIpResolver], which honours
 *   `X-Forwarded-For` only when the immediate peer is in the
 *   operator-configured trusted-proxy list
 *   ([com.medcore.platform.observability.ObservabilityProperties.Proxy.trustedProxies]).
 *   With the default empty list, the resolver returns
 *   [jakarta.servlet.ServletRequest.getRemoteAddr] verbatim — so dev
 *   and test environments cannot be spoofed via XFF.
 *
 *   Closes the 3C carry-forward for proxy-aware client_ip extraction.
 *
 * `user_agent`:
 *   Raw `User-Agent` header. Rule 01 allows this — it is not PHI —
 *   but pathologically long or binary values are absorbed by the
 *   Postgres TEXT column sizing rather than truncated here.
 */
@Component
class RequestMetadataProvider(
    private val clientIpResolver: ProxyAwareClientIpResolver,
) {

    fun current(): RequestMetadata {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            ?: return RequestMetadata.EMPTY
        val request = attributes.request
        return RequestMetadata(
            requestId = MDC.get(MdcKeys.REQUEST_ID)?.takeIf { it.isNotBlank() },
            clientIp = clientIpResolver.resolve(request)?.takeIf { it.isNotBlank() },
            userAgent = request.getHeader(USER_AGENT_HEADER)?.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        const val USER_AGENT_HEADER: String = "User-Agent"
    }
}
