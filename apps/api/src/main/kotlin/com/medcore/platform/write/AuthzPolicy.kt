package com.medcore.platform.write

/**
 * Authorization policy for a single command type [CMD]. Implementations
 * live in their module (tenancy, future patient, future encounter)
 * and encapsulate the four-dimensional authz check described in
 * ADR-007 §4.2:
 *
 *   1. Authenticated caller (enforced by Spring Security before MVC).
 *   2. Role in the target tenant.
 *   3. Authority set (derived from role).
 *   4. Tenant scope (authority applies only to the specific target).
 *
 * [check] returns normally on ALLOW and throws
 * [WriteAuthorizationException] on DENY. The throw carries a
 * [WriteDenialReason] that the auditor emits; the response body is
 * uniform "Access denied." regardless of reason (Rule 01
 * §enumeration).
 */
fun interface AuthzPolicy<CMD> {
    fun check(command: CMD, context: WriteContext)
}
