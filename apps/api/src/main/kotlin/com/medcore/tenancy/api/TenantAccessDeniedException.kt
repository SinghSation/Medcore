package com.medcore.tenancy.api

/**
 * Thrown by the tenancy controller when the authenticated caller does not
 * hold an ACTIVE membership for a requested tenant (or the tenant itself
 * is not ACTIVE). Handled uniformly by [TenancyExceptionHandler] into a
 * 403 with the shared `ErrorResponse` envelope.
 *
 * The exception message is NOT surfaced to the client — it exists for
 * server-side logs only (PHI-free by construction; no user-supplied
 * values are echoed). The client always sees the single, non-enumerating
 * message from the exception handler.
 */
class TenantAccessDeniedException(message: String) : RuntimeException(message)
