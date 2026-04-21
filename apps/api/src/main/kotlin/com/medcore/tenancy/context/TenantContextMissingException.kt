package com.medcore.tenancy.context

/**
 * Thrown by [TenantContext.require] when a route demands a resolved
 * tenant context but the request arrived without a valid
 * `X-Medcore-Tenant` header.
 *
 * No route in Phase 3B.1 calls `require()`; the exception is defensive
 * infrastructure so the failure mode is typed and reviewable the moment
 * a future PHI route starts relying on it. A dedicated
 * `@ExceptionHandler` / mapping from this type to a 400/403 envelope
 * will land in the slice that introduces the first caller.
 *
 * Raised deliberately as a dedicated type rather than `IllegalStateException`
 * so audit hooks (Phase 3C) and exception-handling code can target it
 * precisely without pattern-matching on messages.
 */
class TenantContextMissingException(message: String) : RuntimeException(message)
