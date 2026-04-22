package com.medcore.platform.write

/**
 * Optional domain-command validation hook run by [WriteGate.apply]
 * BEFORE authorization.
 *
 * Distinct from HTTP DTO validation (`@Valid` on controller args)
 * which runs at the Spring MVC boundary against the inbound JSON.
 * The [WriteValidator] sees the canonical *domain command* after
 * any controller-layer mapping. Two-stage validation exists because:
 *
 * - HTTP DTO validation protects against shape-of-wire bugs (wrong
 *   types, missing fields).
 * - [WriteValidator] protects against shape-of-intent bugs (business
 *   invariants: slug must be unique, status transition must be
 *   legal, etc.).
 *
 * Implementations throw any subclass of
 * [jakarta.validation.ConstraintViolationException] OR
 * [IllegalArgumentException] with a message safe for audit logs
 * (no PHI). The Phase 3G error handler translates both to HTTP
 * 422 with the uniform validation envelope.
 */
fun interface WriteValidator<CMD> {
    fun validate(command: CMD)
}
