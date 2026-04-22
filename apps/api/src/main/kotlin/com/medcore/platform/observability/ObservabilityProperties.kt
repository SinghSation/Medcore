package com.medcore.platform.observability

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Typed configuration for the observability substrate (Phase 3F).
 *
 * Bound from `medcore.observability.*` in `application.yaml`. All
 * defaults are production-safe:
 *
 *   - The request-ID header is `X-Request-Id` (industry-standard).
 *   - Inbound values are accepted only when they match [idFormat].
 *     A new UUIDv4 is minted otherwise — this prevents a client from
 *     poisoning correlation with unbounded/adversarial payload.
 *   - The proxy trust list is empty by default, so `X-Forwarded-For`
 *     is IGNORED and [jakarta.servlet.ServletRequest.getRemoteAddr]
 *     is used. Operations layers in a trusted-proxy list (ALB / CDN
 *     public IPs or CIDRs) at deploy time. This choice means no
 *     spoofable default in dev/test.
 */
@ConfigurationProperties(prefix = "medcore.observability")
@Validated
data class ObservabilityProperties(
    val requestId: RequestId = RequestId(),
    val proxy: Proxy = Proxy(),
) {
    data class RequestId(
        /**
         * HTTP header to read and write. Convention: `X-Request-Id`.
         */
        @field:NotBlank
        val headerName: String = "X-Request-Id",

        /**
         * Regex an inbound header MUST match to be propagated. If the
         * inbound value fails this check, the filter mints a fresh
         * UUIDv4 and discards the inbound value. The default accepts
         * lowercase or uppercase UUIDs — other formats (ULIDs,
         * opaque correlation IDs) are explicit opt-ins.
         */
        @field:NotBlank
        val idFormat: String = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",

        /**
         * Hard upper bound on an inbound header value length, applied
         * before regex validation. A pathological 10MB header still
         * produces a fresh UUID and no log spam.
         */
        @field:Positive
        val maxLength: Int = 128,

        /**
         * When true, the response echoes the request id on the same
         * header name. Always on in production — responses are the
         * primary way a caller correlates a log line to a request.
         */
        val echoOnResponse: Boolean = true,
    )

    data class Proxy(
        /**
         * Comma-separated list of trusted proxy IPs or CIDR ranges.
         * Empty in dev/test (the application is addressed directly
         * and X-Forwarded-For is not trusted). Production sets the
         * ALB / CloudFront / CDN egress range(s) here.
         */
        val trustedProxies: List<String> = emptyList(),

        /**
         * Header name used by the upstream proxy. Convention:
         * `X-Forwarded-For`.
         */
        @field:NotBlank
        val forwardedForHeader: String = "X-Forwarded-For",
    )
}
