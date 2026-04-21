package com.medcore.platform.audit

import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * Reads request-scoped metadata for audit writes.
 *
 * Safe to call outside a request scope: returns [RequestMetadata.EMPTY]
 * when no `ServletRequestAttributes` are bound (e.g., background jobs).
 *
 * `X-Request-Id`:
 *   Medcore has not yet adopted a central request-ID generator. If a
 *   caller (reverse proxy, test harness) supplies the header we
 *   propagate it into audit rows; otherwise the field is null. Adding
 *   a generator is tracked as a follow-up tightening — it is not a
 *   blocker for ADR-003 Audit v1, which treats `request_id` as
 *   nullable.
 *
 * `client_ip`:
 *   Uses [jakarta.servlet.ServletRequest.getRemoteAddr]. At 3C the
 *   backend sits directly behind the dev container port; proxy /
 *   XFF-aware IP extraction is a later concern and will land with the
 *   edge config ADR.
 *
 * `user_agent`:
 *   Raw `User-Agent` header. Rule 01 allows this — it is not PHI — but
 *   callers who encounter a pathologically long or binary value should
 *   rely on TEXT column sizing in Postgres to absorb it rather than
 *   truncating here.
 */
@Component
class RequestMetadataProvider {

    fun current(): RequestMetadata {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            ?: return RequestMetadata.EMPTY
        val request = attributes.request
        return RequestMetadata(
            requestId = request.getHeader(REQUEST_ID_HEADER)?.takeIf { it.isNotBlank() },
            clientIp = request.remoteAddr?.takeIf { it.isNotBlank() },
            userAgent = request.getHeader(USER_AGENT_HEADER)?.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        const val REQUEST_ID_HEADER: String = "X-Request-Id"
        const val USER_AGENT_HEADER: String = "User-Agent"
    }
}
