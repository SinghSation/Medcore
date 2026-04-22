package com.medcore.platform.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.medcore.platform.api.ErrorCodes
import com.medcore.platform.api.ErrorResponses
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler

/**
 * Writes the unified Medcore 403 envelope for authenticated-but-
 * unauthorized requests at the Spring Security boundary.
 *
 * Fires when Spring raises [AccessDeniedException] — typically from
 * `@PreAuthorize` / `@PostAuthorize` / method security / or explicit
 * `authorizeHttpRequests { ... authenticated() }` rules that the
 * authenticated caller fails.
 *
 * Separate from [com.medcore.tenancy.context.TenantContextFilter]'s
 * 403 path (which is a servlet-filter decision, not an MVC /
 * method-security one) and from [com.medcore.tenancy.api.TenancyExceptionHandler]
 * (which handles `TenantAccessDeniedException` at the controller
 * layer). Those emit code [ErrorCodes.TENANT_FORBIDDEN]; this emits
 * [ErrorCodes.AUTH_FORBIDDEN]. Both codes MUST produce an identical
 * `"Access denied."` message — callers distinguish the two only by
 * `code`, never by message (Rule 01 §enumeration).
 *
 * Audit note (Phase 3G deliberate non-goal):
 *   No audit event is emitted here. Authorization-denial audit lands
 *   in Phase 3J with RBAC — without concrete role-driven rules,
 *   `AccessDeniedException` cannot be routed to meaningful
 *   attribution, and emitting vague `authz.denied` rows now would
 *   add noise. The carry-forward is tracked.
 */
class MedcoreAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(
            response.outputStream,
            ErrorResponses.of(
                code = ErrorCodes.AUTH_FORBIDDEN,
                message = MESSAGE,
            ),
        )
    }

    companion object {
        /**
         * IDENTICAL to the message emitted by tenancy's 403 path.
         * Callers MUST distinguish auth-denied from tenancy-denied via
         * `code`, never message (Rule 01 §enumeration).
         */
        const val MESSAGE: String = "Access denied."
    }
}
