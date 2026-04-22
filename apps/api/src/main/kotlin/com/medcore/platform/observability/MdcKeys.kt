package com.medcore.platform.observability

/**
 * Canonical MDC (Mapped Diagnostic Context) keys used across Medcore.
 *
 * Every log line emitted inside a request scope carries these keys as
 * structured fields. Population points:
 *
 *   - [REQUEST_ID] — populated by [RequestIdFilter] on every inbound
 *     HTTP request (pre-security so 401s and auth failures carry the
 *     correlation id).
 *   - [USER_ID]    — populated by [MdcUserIdFilter] after Spring
 *     Security's filter chain resolves the authentication (so it
 *     appears in logs emitted by the tenant filter, controllers, and
 *     service methods).
 *   - [TENANT_ID]  — populated by the tenancy filter once header-based
 *     tenant resolution succeeds.
 *
 * Keys are read downstream by:
 *   - structured log output (Spring Boot 3.4 structured logging
 *     includes MDC by default),
 *   - [com.medcore.platform.audit.RequestMetadataProvider], which
 *     lifts the filter-generated [REQUEST_ID] into every audit row so
 *     the response header, log line, and `audit_event.request_id`
 *     column all match.
 *
 * The keys are intentionally scoped — no ad-hoc additions. Expanding
 * this list is a Tier-2 observability slice with a review pack.
 */
object MdcKeys {
    const val REQUEST_ID: String = "request_id"
    const val USER_ID: String = "user_id"
    const val TENANT_ID: String = "tenant_id"
}
