package com.medcore.tenancy.api

import com.medcore.platform.api.ErrorCodes
import com.medcore.platform.api.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Turns tenancy's domain exceptions into the shared [ErrorResponse]
 * envelope. Scoped to the tenancy controller package so it doesn't swallow
 * exceptions other modules raise.
 *
 * The uniform message deliberately does NOT distinguish unknown-slug from
 * not-a-member from suspended — enumeration is a side channel (Rule 01,
 * §authorization).
 */
@RestControllerAdvice(basePackageClasses = [TenantsController::class])
class TenancyExceptionHandler {

    @ExceptionHandler(TenantAccessDeniedException::class)
    fun onAccessDenied(ex: TenantAccessDeniedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ErrorResponse(
                code = ErrorCodes.TENANT_FORBIDDEN,
                message = "Access to the requested tenant is denied.",
            ),
        )
}
