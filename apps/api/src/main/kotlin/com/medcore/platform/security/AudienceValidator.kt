package com.medcore.platform.security

import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt

/**
 * Rejects a JWT whose `aud` claim does not include the configured audience.
 *
 * Attached only when `medcore.oidc.audience` is set; otherwise the default
 * issuer validator stands alone. The error message identifies the
 * configuration knob, never echoes the offending token content.
 */
class AudienceValidator(private val expected: String) : OAuth2TokenValidator<Jwt> {

    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val audiences: List<String> = token.audience ?: emptyList()
        return if (audiences.contains(expected)) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(
                OAuth2Error(
                    "invalid_token",
                    "The required audience is missing",
                    null,
                ),
            )
        }
    }
}
