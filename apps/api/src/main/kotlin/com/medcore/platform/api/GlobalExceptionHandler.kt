package com.medcore.platform.api

import com.medcore.clinical.patient.service.DuplicatePatientWarningException
import com.medcore.platform.write.WriteConflictException
import com.medcore.platform.write.WriteValidationException
import com.medcore.tenancy.context.TenantContextMissingException
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.ConstraintViolationException
import java.sql.SQLException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Context-wide [RestControllerAdvice] that maps every exception class
 * Medcore cares about to the unified [ErrorResponse] envelope with
 * correct HTTP status. Closes the uniform 401 / tenant-context
 * carry-forwards from 3B.1 (3G roadmap row).
 *
 * Precedence rule (per Spring's `@Order` + `@RestControllerAdvice`
 * selection):
 *
 *   1. Module-specific advisers (e.g. [com.medcore.tenancy.api.TenancyExceptionHandler])
 *      run FIRST — they carry `basePackageClasses` scoping and match
 *      narrower exception types AND narrower path patterns. Explicit
 *      `@Order(HIGHEST_PRECEDENCE)` is applied to each module adviser.
 *   2. This class runs next — typed handlers for the exception classes
 *      listed in §5 of the 3G plan. Placed at
 *      `@Order(LOWEST_PRECEDENCE - 10)` so module-specific advisers
 *      always win.
 *   3. [onUncaught] is the absolute fallback. It handles any `Throwable`
 *      not caught by a more specific handler in this class or a
 *      module adviser. Under no circumstance is a stack trace, class
 *      name, SQL fragment, or cause-chain detail echoed to the client.
 *
 * Deliberate exclusions (Phase 3G non-goals):
 *   - **400 Bad Request is NOT normalised here.** Spring's default
 *     handling for `HttpMessageNotReadableException` (malformed JSON),
 *     `MissingServletRequestParameterException`, type-mismatch, etc.
 *     continues to emit Spring-shaped responses. Tracked as carry-
 *     forward for a future slice when a real caller cares.
 *   - **No RFC 7807 / Problem Details.**
 *   - **No localisation / i18n of error messages.**
 *   - **No content-type negotiation** — every response is `application/json`.
 *   - **No `Retry-After` or other retry-semantics headers.**
 *   - **No authorisation-denial audit emission** — deferred to Phase 3J
 *     with RBAC. `AccessDeniedException` is handled by
 *     [com.medcore.platform.security.MedcoreAccessDeniedHandler] at
 *     the security filter chain, NOT here.
 *
 * Leakage discipline:
 *   - Every response message is a fixed, non-user-controlled string.
 *   - No exception message, class name, SQL fragment, file path, or
 *     PHI is echoed in any response body.
 *   - Validation responses carry `details.validationErrors` as
 *     `[{field, code}]` — field NAMES only (never values — rejected
 *     patient DOB field values would leak PHI).
 *   - Full exception detail is ONLY logged, not emitted — `requestId`
 *     in the response body correlates to the full log line.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    // --- 403 Forbidden (method-security denials) ---

    /**
     * `@PreAuthorize` / `@PostAuthorize` denials raise
     * [org.springframework.security.authorization.AuthorizationDeniedException]
     * (a subclass of [AccessDeniedException]) from within the controller
     * invocation. Those do NOT flow through the security filter
     * chain's `AccessDeniedHandler` — they propagate via Spring MVC
     * exception handling. This handler makes sure the unified 403
     * envelope is emitted regardless of where in the chain the
     * denial originates. Message is identical to
     * [com.medcore.platform.security.MedcoreAccessDeniedHandler]'s
     * (Rule 01 §enumeration).
     *
     * Double-handling is not possible by construction:
     *   - Filter-chain denials (e.g., `authorizeHttpRequests { authenticated() }`
     *     rejection) are caught by Spring's `ExceptionTranslationFilter`,
     *     which invokes the configured `AccessDeniedHandler` —
     *     [com.medcore.platform.security.MedcoreAccessDeniedHandler] —
     *     which writes the response and commits it. The request never
     *     reaches Spring MVC's `DispatcherServlet`; this handler is
     *     never consulted for that code path.
     *   - Method-security denials fire AFTER the filter chain has
     *     completed successfully (the filter chain does not evaluate
     *     `@PreAuthorize`). The exception bubbles through the
     *     controller to `DispatcherServlet`, which dispatches it to
     *     this handler. The filter-chain handler is not invoked for
     *     this code path.
     *
     * The two paths are mutually exclusive; no `isCommitted()` guard
     * is required. If a future slice introduces an overlap mode
     * (e.g., a custom filter that re-raises `AccessDeniedException`
     * post-commit), that slice MUST re-visit this assumption.
     */
    @ExceptionHandler(AccessDeniedException::class)
    fun onAccessDenied(ex: AccessDeniedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ErrorResponses.of(
                code = ErrorCodes.AUTH_FORBIDDEN,
                message = "Access denied.",
            ),
        )

    // --- 422 Unprocessable Entity ---

    @ExceptionHandler(TenantContextMissingException::class)
    fun onTenantContextMissing(
        ex: TenantContextMissingException,
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ErrorResponses.of(
                code = ErrorCodes.TENANCY_CONTEXT_REQUIRED,
                message = "Tenant context is required for this request.",
            ),
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun onBeanValidation(
        ex: MethodArgumentNotValidException,
    ): ResponseEntity<ErrorResponse> {
        val fieldErrors = ex.bindingResult.fieldErrors.map { err ->
            // Field names only. `code` from Spring is the violated-
            // constraint short name (e.g. "NotBlank", "Size") —
            // framework-level, not user-derived. No `rejectedValue` —
            // it may carry PHI.
            mapOf("field" to err.field, "code" to (err.code ?: "Invalid"))
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ErrorResponses.of(
                code = ErrorCodes.REQUEST_VALIDATION_FAILED,
                message = "One or more fields failed validation.",
                details = mapOf("validationErrors" to fieldErrors),
            ),
        )
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun onHandlerMethodValidation(
        ex: HandlerMethodValidationException,
    ): ResponseEntity<ErrorResponse> {
        // Consistency invariant: every 422 validation emission carries
        // `details.validationErrors = [{field, code}]` — no variation
        // between handlers, no missing wrapper, no rename. Spring's
        // HandlerMethodValidationException exposes per-parameter
        // `ParameterValidationResult`s; each result has a
        // `resolvableErrors` list whose `defaultMessage` we do NOT
        // emit (messages can carry values). We emit parameter name +
        // the violated-constraint short code only.
        val fieldErrors = ex.parameterValidationResults.flatMap { result ->
            val field = result.methodParameter.parameterName ?: "argument${result.methodParameter.parameterIndex}"
            result.resolvableErrors.map { err ->
                mapOf("field" to field, "code" to (err.codes?.firstOrNull() ?: "Invalid"))
            }
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ErrorResponses.of(
                code = ErrorCodes.REQUEST_VALIDATION_FAILED,
                message = "One or more request parameters failed validation.",
                details = mapOf("validationErrors" to fieldErrors),
            ),
        )
    }

    /**
     * Phase 3J.2: [WriteValidationException] maps to the same 422
     * envelope and the same `details.validationErrors` shape as
     * bean-validation failures. Validators run BEFORE a transaction
     * opens (see `WriteGate`), so there is nothing to roll back —
     * the caller receives a uniform 422 regardless of whether
     * Spring's `@Valid` rejected the payload or the domain validator
     * did. Field + code only; no message, no cause, no value leak.
     */
    @ExceptionHandler(WriteValidationException::class)
    fun onWriteValidation(
        ex: WriteValidationException,
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ErrorResponses.of(
                code = ErrorCodes.REQUEST_VALIDATION_FAILED,
                message = "One or more fields failed validation.",
                details = mapOf(
                    "validationErrors" to listOf(
                        mapOf("field" to ex.field, "code" to ex.code),
                    ),
                ),
            ),
        )

    @ExceptionHandler(ConstraintViolationException::class)
    fun onConstraintViolation(
        ex: ConstraintViolationException,
    ): ResponseEntity<ErrorResponse> {
        // jakarta.validation (as opposed to Spring's binding result).
        // Path parameter / @Validated service-level violations arrive
        // here. Treat same as bean-validation — 422, field-names only.
        val fieldErrors = ex.constraintViolations.map { v ->
            mapOf(
                "field" to v.propertyPath.toString(),
                "code" to v.constraintDescriptor.annotation.annotationClass.simpleName.orEmpty(),
            )
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ErrorResponses.of(
                code = ErrorCodes.REQUEST_VALIDATION_FAILED,
                message = "One or more constraints failed validation.",
                details = mapOf("validationErrors" to fieldErrors),
            ),
        )
    }

    // --- 409 Conflict ---

    /**
     * Phase 3J.N: [WriteConflictException] maps to 409
     * `resource.conflict` with a structured `details.reason`
     * carrying the handler's short slug. Used by aggregate-state
     * invariants (e.g., `LastOwnerInvariant` refusing to demote
     * the last active OWNER of a tenant). Distinct from the
     * optimistic-lock / unique-constraint paths below — those
     * are DB-layer; this is app-layer enforcement of a
     * multi-row invariant inside the gate's transaction.
     */
    @ExceptionHandler(WriteConflictException::class)
    fun onWriteConflict(
        ex: WriteConflictException,
    ): ResponseEntity<ErrorResponse> {
        // Merge `reason` (always) + any structured details the
        // handler attached (Phase 4C.4: `existingEncounterId` on
        // double-start conflicts). Values are safe-to-wire
        // primitives — the exception contract forbids PHI here.
        val merged: MutableMap<String, Any> = linkedMapOf("reason" to ex.code)
        ex.details?.forEach { (k, v) -> merged[k] = v }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponses.of(
                code = ErrorCodes.RESOURCE_CONFLICT,
                message = "The request conflicts with the current state of the resource.",
                details = merged,
            ),
        )
    }

    @ExceptionHandler(OptimisticLockingFailureException::class, ObjectOptimisticLockingFailureException::class)
    fun onOptimisticLock(
        ex: Exception,
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponses.of(
                code = ErrorCodes.RESOURCE_CONFLICT,
                message = "The resource was modified by another request. Retry with the latest version.",
            ),
        )

    /**
     * Phase 4A.2: duplicate-patient warning on patient create.
     * `details.candidates` carries `[{patientId, mrn}]` — minimal
     * disclosure. Clients retry with `X-Confirm-Duplicate: true`
     * after verifying the candidate is a genuinely different
     * patient. Deliberately distinct from `resource.conflict`
     * because the retry contract differs.
     */
    @ExceptionHandler(DuplicatePatientWarningException::class)
    fun onDuplicatePatientWarning(
        ex: DuplicatePatientWarningException,
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponses.of(
                code = ErrorCodes.CLINICAL_PATIENT_DUPLICATE_WARNING,
                message = "A candidate duplicate patient already exists. Confirm " +
                    "by resending the request with the X-Confirm-Duplicate: true header.",
                details = mapOf(
                    "candidates" to ex.candidates.map { candidate ->
                        mapOf(
                            "patientId" to candidate.patientId.toString(),
                            "mrn" to candidate.mrn,
                        )
                    },
                ),
            ),
        )

    // --- 428 Precondition Required ---

    /**
     * Phase 4A.2: `If-Match` header missing on a required-conditional
     * PATCH. 428 distinguishes "you forgot the header" from 409
     * "you sent a stale version". See [PreconditionRequiredException]
     * KDoc for the full rationale.
     */
    @ExceptionHandler(PreconditionRequiredException::class)
    fun onPreconditionRequired(
        ex: PreconditionRequiredException,
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body(
            ErrorResponses.of(
                code = ErrorCodes.PRECONDITION_REQUIRED,
                message = "A required precondition header is missing.",
            ),
        )

    /**
     * [DataIntegrityViolationException] covers several distinct
     * conditions. Only true **state conflicts** — uniqueness, FK —
     * map to 409. Check / NOT-NULL violations indicate **input
     * validation failures** the caller can fix and map to 422. Any
     * other integrity violation falls through to 500 (treated as a
     * bug; never a predictable client-fault path).
     *
     * Decision is made by inspecting the PostgreSQL SQLSTATE on the
     * underlying [SQLException]. The SQLSTATE classification is
     * driver-independent (class 23 = integrity-constraint violation)
     * — using it here avoids parsing driver-specific constraint names
     * or error-message text.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun onDataIntegrityViolation(
        ex: DataIntegrityViolationException,
    ): ResponseEntity<ErrorResponse> {
        val sqlState = findSqlState(ex)
        return when (sqlState) {
            SQLSTATE_UNIQUE_VIOLATION, SQLSTATE_FK_VIOLATION, SQLSTATE_EXCLUSION_VIOLATION ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ErrorResponses.of(
                        code = ErrorCodes.RESOURCE_CONFLICT,
                        message = "The request conflicts with the current state of the resource.",
                    ),
                )
            SQLSTATE_NOT_NULL_VIOLATION, SQLSTATE_CHECK_VIOLATION ->
                ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                    ErrorResponses.of(
                        code = ErrorCodes.REQUEST_VALIDATION_FAILED,
                        message = "The request violates a data constraint.",
                    ),
                )
            else -> {
                log.error(
                    "data integrity violation with unrecognised SQLSTATE={}; treating as server error",
                    sqlState ?: "<none>",
                    ex,
                )
                fallback(ex)
            }
        }
    }

    // --- 404 Not Found ---

    @ExceptionHandler(
        NoResourceFoundException::class,
        EntityNotFoundException::class,
        EmptyResultDataAccessException::class,
    )
    fun onNotFound(ex: Exception): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponses.of(
                code = ErrorCodes.RESOURCE_NOT_FOUND,
                message = "The requested resource was not found.",
            ),
        )

    // --- 5xx fallback ---

    /**
     * Absolute fallback. Any `Throwable` not caught by a more specific
     * handler lands here. The response body carries zero information
     * about the cause — correlation to the underlying exception is
     * exclusively via the `requestId` field (matches the full stack
     * trace logged below).
     */
    @ExceptionHandler(Throwable::class)
    fun onUncaught(ex: Throwable): ResponseEntity<ErrorResponse> {
        log.error("uncaught exception in request handler", ex)
        return fallback(ex)
    }

    private fun fallback(ex: Throwable): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponses.of(
                code = ErrorCodes.SERVER_ERROR,
                message = "An unexpected error occurred.",
            ),
        )

    private fun findSqlState(ex: Throwable): String? {
        var cause: Throwable? = ex
        while (cause != null) {
            if (cause is SQLException) return cause.sqlState
            if (cause.cause === cause) return null
            cause = cause.cause
        }
        return null
    }

    private companion object {
        // PostgreSQL SQLSTATE class 23 — integrity constraint violation.
        const val SQLSTATE_UNIQUE_VIOLATION: String = "23505"
        const val SQLSTATE_FK_VIOLATION: String = "23503"
        const val SQLSTATE_EXCLUSION_VIOLATION: String = "23P01"
        const val SQLSTATE_NOT_NULL_VIOLATION: String = "23502"
        const val SQLSTATE_CHECK_VIOLATION: String = "23514"
    }
}
