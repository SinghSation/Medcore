package com.medcore.platform.audit

/**
 * Request-scoped metadata lifted into every audit row by the writer.
 *
 * Never contains PHI by construction — the fields here are populated by
 * [RequestMetadataProvider] from HTTP-level primitives only (client IP,
 * user agent string, correlation header). No request body, no
 * authorization header, no arbitrary headers.
 */
data class RequestMetadata(
    val requestId: String?,
    val clientIp: String?,
    val userAgent: String?,
) {
    companion object {
        val EMPTY: RequestMetadata = RequestMetadata(null, null, null)
    }
}
