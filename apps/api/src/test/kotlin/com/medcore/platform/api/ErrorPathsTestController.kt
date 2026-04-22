package com.medcore.platform.api

import com.medcore.tenancy.context.TenantContextMissingException
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.sql.SQLException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Test-only controller that forces each exception path the global
 * handler is supposed to catch. Mounted only under a profile gate so
 * it never ships to production even by accident.
 *
 * Gate:
 *   - `@ConditionalOnProperty("medcore.testing.error-paths-controller.enabled"
 *     matchIfMissing = false)` — the controller mounts ONLY when a
 *     test explicitly sets the property. Production configs never
 *     set it; `matchIfMissing = false` means absence of the
 *     property is a hard no-mount. The class lives under
 *     `src/test/kotlin` so it cannot ship in the production jar
 *     regardless.
 *
 * Endpoints are deliberately verbose so the test suite can exercise
 * each branch of `GlobalExceptionHandler` in isolation.
 */
@RestController
@RequestMapping("/api/test/errors")
@ConditionalOnProperty(
    value = ["medcore.testing.error-paths-controller.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
@Validated
class ErrorPathsTestController {

    // --- 403 (Spring AccessDenied via denyAll) ---

    @GetMapping("/access-denied")
    @PreAuthorize("denyAll()")
    fun accessDenied(): String = "unreachable"

    // --- 404 ---

    @GetMapping("/entity-not-found")
    fun entityNotFound(): Nothing =
        throw EntityNotFoundException("test-only: entity not found")

    @GetMapping("/empty-result")
    fun emptyResult(): Nothing =
        throw EmptyResultDataAccessException("test-only: expected 1, got 0", 1)

    // --- 409 ---

    @GetMapping("/optimistic-lock")
    fun optimisticLock(): Nothing =
        throw OptimisticLockingFailureException("test-only: version mismatch")

    @GetMapping("/unique-violation")
    fun uniqueViolation(): Nothing =
        throw DataIntegrityViolationException(
            "test-only: duplicate key",
            SQLException("duplicate key value violates unique constraint", "23505"),
        )

    @GetMapping("/fk-violation")
    fun fkViolation(): Nothing =
        throw DataIntegrityViolationException(
            "test-only: foreign key violation",
            SQLException("fk violation", "23503"),
        )

    // --- 422 (validation) ---

    @GetMapping("/path-validation")
    fun pathValidation(
        @RequestParam @Min(1) n: Int,
    ): String = "n=$n"

    @PostMapping("/body-validation")
    fun bodyValidation(@Valid @RequestBody body: ValidatableBody): String = "ok"

    @GetMapping("/not-null-violation")
    fun notNullViolation(): Nothing =
        throw DataIntegrityViolationException(
            "test-only: not-null violation",
            SQLException("null value in column violates not-null constraint", "23502"),
        )

    @GetMapping("/check-violation")
    fun checkViolation(): Nothing =
        throw DataIntegrityViolationException(
            "test-only: check constraint violation",
            SQLException("check violation", "23514"),
        )

    // --- 422 (tenancy.context.required) ---

    @GetMapping("/tenant-context-missing")
    fun tenantContextMissing(): Nothing =
        throw TenantContextMissingException("test-only: required tenant context absent")

    // --- 500 (fallback) ---

    @GetMapping("/uncaught")
    fun uncaught(): Nothing =
        throw IllegalStateException("test-only: something-internal SELECT * FROM patients WHERE id=123")

    @GetMapping("/unknown-data-integrity")
    fun unknownDataIntegrity(): Nothing =
        throw DataIntegrityViolationException(
            "test-only: unrecognised sqlstate",
            SQLException("some other integrity issue", "99999"),
        )

    data class ValidatableBody(
        @field:NotBlank val name: String,
        @field:Min(1) val amount: Int,
    )
}
