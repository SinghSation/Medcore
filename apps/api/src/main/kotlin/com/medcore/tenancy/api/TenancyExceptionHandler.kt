package com.medcore.tenancy.api

import com.medcore.platform.api.ErrorCodes
import com.medcore.platform.api.ErrorResponse
import com.medcore.platform.api.ErrorResponses
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Turns tenancy's domain exceptions into the shared [ErrorResponse]
 * envelope. Scoped to the tenancy controller package so it doesn't
 * swallow exceptions other modules raise.
 *
 * Precedence: `@Order(Ordered.HIGHEST_PRECEDENCE)` ensures this
 * module-specific adviser wins over
 * [com.medcore.platform.api.GlobalExceptionHandler] when both match.
 * Without an explicit order, `LOWEST_PRECEDENCE` ties with the global
 * handler and the resolution is undefined. Phase 3G locks this.
 *
 * The message is deliberately IDENTICAL to
 * [com.medcore.platform.security.MedcoreAccessDeniedHandler]'s —
 * callers distinguish the two 403 paths only by `code`, never by
 * message. Leaking "tenant" in the message would hand a probing
 * caller an enumeration signal about whether a given path is
 * tenancy-scoped (Rule 01 §authorization).
 */
@RestControllerAdvice(basePackageClasses = [TenantsController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class TenancyExceptionHandler {

    @ExceptionHandler(TenantAccessDeniedException::class)
    fun onAccessDenied(ex: TenantAccessDeniedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ErrorResponses.of(
                code = ErrorCodes.TENANT_FORBIDDEN,
                message = "Access denied.",
            ),
        )
}
