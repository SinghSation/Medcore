package com.medcore.platform.write

import org.springframework.security.access.AccessDeniedException

/**
 * Thrown by [AuthzPolicy.check] when a mutation is refused at the
 * authorization boundary.
 *
 * Extends Spring's [AccessDeniedException] so Medcore's existing
 * 3G error handler for `AccessDeniedException` (→ 403, code
 * `auth.forbidden`, message "Access denied.") applies uniformly.
 * The [reason] field is NEVER echoed to the client — it is the
 * audit-event `reason` slug captured by [WriteAuditor.onDenied].
 */
class WriteAuthorizationException(
    val reason: WriteDenialReason,
) : AccessDeniedException("Write authorization denied: ${reason.code}")
