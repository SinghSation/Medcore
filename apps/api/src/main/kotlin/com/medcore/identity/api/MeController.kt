package com.medcore.identity.api

import com.medcore.platform.security.MedcorePrincipal
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * GET /api/v1/me — contract defined in
 * `packages/schemas/openapi/identity/identity.yaml`.
 *
 * Authentication is enforced by [com.medcore.platform.security.SecurityConfig].
 * By the time a request reaches this handler, the JWT has been validated and
 * the identity row has been JIT-provisioned by the platform's
 * `PrincipalResolver` SPI (implemented in the identity module).
 */
@RestController
@RequestMapping("/api/v1")
class MeController {

    @GetMapping("/me", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun me(@AuthenticationPrincipal principal: MedcorePrincipal): MeResponse =
        MeResponse(
            userId = principal.userId.toString(),
            issuer = principal.issuerSubject.issuer,
            subject = principal.issuerSubject.subject,
            email = principal.email,
            emailVerified = principal.emailVerified,
            displayName = principal.displayName,
            preferredUsername = principal.preferredUsername,
            status = MeStatus.valueOf(principal.status.name),
        )
}
