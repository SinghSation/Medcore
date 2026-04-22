package com.medcore.platform.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Populates the request correlation id for every inbound HTTP request.
 *
 * Registered **before** Spring Security's filter chain (see
 * [RequestIdFilterRegistration]) so:
 *   - 401 responses from the security chain carry the correlation id
 *     in the response header AND in every log line emitted during
 *     auth rejection,
 *   - [com.medcore.platform.security.AuditingAuthenticationEntryPoint]
 *     and the rest of the identity/tenancy audit path see the id via
 *     MDC without needing to re-read the request,
 *   - all downstream filters (including [com.medcore.tenancy.context.TenantContextFilter])
 *     inherit MDC with the correlation id already populated.
 *
 * Source of truth rules:
 *   - If the inbound header matches the configured format AND is at
 *     or below [ObservabilityProperties.RequestId.maxLength], the
 *     inbound value is accepted verbatim.
 *   - Otherwise (missing, too long, malformed) a fresh UUIDv4 is
 *     minted. The filter NEVER echoes an untrusted inbound value.
 *
 * Response echo:
 *   - When [ObservabilityProperties.RequestId.echoOnResponse] is true
 *     (default), the response header is set to the accepted or
 *     generated id before the filter chain is invoked, so the header
 *     is present even when the downstream chain never writes the
 *     body (e.g., security rejections committed by the entry point).
 *
 * MDC lifecycle:
 *   - [MdcKeys.REQUEST_ID] is set before `chain.doFilter` and cleared
 *     in a `finally` so the value never leaks to pooled threads after
 *     the request completes.
 *
 * Scope:
 *   - Every path (actuator endpoints, `/error`, and anything outside
 *     the `/api` tree). Correlation is cheap and universally useful;
 *     the filter does not discriminate by URL.
 */
class RequestIdFilter(
    private val properties: ObservabilityProperties,
) : OncePerRequestFilter() {

    private val idPattern: Regex = Regex(properties.requestId.idFormat)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val accepted = acceptInbound(request) ?: generate()

        if (properties.requestId.echoOnResponse) {
            response.setHeader(properties.requestId.headerName, accepted)
        }

        // Idempotent — if a misconfigured upstream filter already set
        // the MDC key, overwrite with the request-scoped value.
        MDC.put(MdcKeys.REQUEST_ID, accepted)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MdcKeys.REQUEST_ID)
        }
    }

    /**
     * Returns the inbound header value if it is trustworthy; null
     * otherwise (caller mints a fresh id).
     */
    private fun acceptInbound(request: HttpServletRequest): String? {
        val raw = request.getHeader(properties.requestId.headerName) ?: return null
        if (raw.isBlank()) return null
        if (raw.length > properties.requestId.maxLength) return null
        if (!idPattern.matches(raw)) return null
        return raw
    }

    private fun generate(): String = UUID.randomUUID().toString()
}
