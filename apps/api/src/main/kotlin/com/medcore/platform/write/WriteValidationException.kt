package com.medcore.platform.write

/**
 * Thrown by a [WriteValidator] to signal a domain-level validation
 * failure that the caller can fix (Phase 3J.2).
 *
 * ### Why this exists
 *
 * `@Valid @RequestBody` on the controller handles every
 * annotation-expressible constraint (e.g., `@NotBlank`, `@Size`) —
 * those land as `MethodArgumentNotValidException` and the Phase 3G
 * [com.medcore.platform.api.GlobalExceptionHandler] already maps
 * them to 422 with `details.validationErrors = [{field, code}]`.
 *
 * Validators handle checks annotations cannot express: post-trim
 * emptiness, cross-field invariants, control-character rejection,
 * reference-data lookups that would go stale inside a static
 * annotation. These throws arrive at the handler as
 * [WriteValidationException], which maps to the same 422 envelope
 * and the same `details.validationErrors` shape so callers see a
 * uniform wire contract regardless of which layer rejected the
 * input.
 *
 * ### Leakage discipline
 *
 * - [field] — field name on the request DTO. Never a value.
 * - [code] — short stable validator-id (e.g., `"blank"`, `"too_long"`,
 *   `"control_chars"`). Never a rejected-value echo, never a free-
 *   form message that could carry PHI.
 *
 * The handler does NOT echo [cause] or any `message` content into
 * the HTTP response; those surface only in structured logs,
 * correlated to the caller via `requestId`.
 */
class WriteValidationException(
    val field: String,
    val code: String,
    cause: Throwable? = null,
) : RuntimeException("validation failed: field=$field code=$code", cause)
