package com.medcore.platform.write

/**
 * Thrown by a handler when the mutation would violate an
 * aggregate-state invariant — e.g., the last-OWNER invariant
 * enforced by `LastOwnerInvariant` in Phase 3J.N, or future
 * "minimum active user" / cross-entity rules.
 *
 * ### Why this exists
 *
 * Phase 3J.2 introduced [WriteValidationException] for 422 paths
 * (request shape invalid). Phase 3J.N introduces this sibling for
 * 409 paths (state conflict — the request is well-formed but
 * conflicts with the CURRENT state of the database). The two
 * exceptions map to different HTTP statuses with different
 * semantics:
 *
 * - 422 `request.validation_failed` — the caller's input is
 *   structurally wrong; fix the body and retry.
 * - 409 `resource.conflict` — the caller's input is well-formed,
 *   but the current database state makes the operation invalid;
 *   the caller cannot fix this with a simple retry (another
 *   actor may need to act first).
 *
 * This is the distinction the [com.medcore.platform.api.GlobalExceptionHandler]
 * already draws between Bean Validation and optimistic-lock /
 * unique-constraint failures (Phase 3G). [WriteConflictException]
 * gives handlers a clean, typed way to surface app-layer aggregate-
 * invariant violations without shoehorning them into
 * `DataIntegrityViolationException` (which requires a SQLSTATE we
 * do not have) or [OptimisticLockingFailureException] (which has
 * a specific JPA semantic).
 *
 * ### Leakage discipline
 *
 * [code] — short stable slug (e.g., `"last_owner_in_tenant"`).
 * Never user-supplied data, never a free-form message. The 3G
 * handler emits only `{code, message, details.reason}`. [cause]
 * and the exception's `message` are logged, never echoed.
 */
class WriteConflictException(
    val code: String,
    cause: Throwable? = null,
) : RuntimeException("write conflict: code=$code", cause)
