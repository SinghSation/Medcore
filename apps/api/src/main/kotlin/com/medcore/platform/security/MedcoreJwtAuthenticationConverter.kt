package com.medcore.platform.security

import com.medcore.platform.config.MedcoreOidcProperties
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt

/**
 * Turns a validated [Jwt] into a Medcore-native authentication token whose
 * `principal` is a [MedcorePrincipal]. Delegates principal materialisation
 * (including JIT provisioning) to the [PrincipalResolver] SPI so this
 * converter stays free of persistence concerns.
 *
 * The [Jwt] is read locally here and never handed to downstream code — the
 * converter extracts every field it needs into a [PrincipalResolutionCommand]
 * and the token object is discarded. By the time this runs, Spring Security
 * has already verified the token's signature, issuer, expiry, nbf, and
 * audience (when configured).
 */
class MedcoreJwtAuthenticationConverter(
    private val oidcProperties: MedcoreOidcProperties,
    private val principalResolver: PrincipalResolver,
) : Converter<Jwt, AbstractAuthenticationToken> {

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val claims = oidcProperties.claims
        val subject = jwt.getClaimAsString(claims.subject)
            ?: throw IllegalStateException("JWT is missing the '${claims.subject}' claim after validation")
        val issuer = jwt.issuer?.toString()
            ?: throw IllegalStateException("JWT is missing 'iss' after validation")

        val principal = principalResolver.resolve(
            PrincipalResolutionCommand(
                issuerSubject = IssuerSubject(issuer = issuer, subject = subject),
                email = jwt.getClaimAsString(claims.email),
                emailVerified = jwt.getClaim<Boolean>(claims.emailVerified) ?: false,
                displayName = jwt.getClaimAsString(claims.name),
                preferredUsername = jwt.getClaimAsString(claims.preferredUsername),
                issuedAt = jwt.issuedAt,
                expiresAt = jwt.expiresAt,
            ),
        )

        // No scope-derived authorities yet; authorization lives at the module
        // boundary in a later slice. Authenticated == has a valid token AND
        // has been provisioned into identity.user.
        return MedcoreAuthenticationToken(principal)
    }
}

/**
 * Carries [MedcorePrincipal] as both the `principal` and the authentication
 * `name`, and is pre-authenticated (the underlying JWT was validated by
 * Spring Security before this token was minted).
 *
 * `getCredentials()` deliberately returns a redacted sentinel rather than
 * the JWT. Credentials were consumed during validation; exposing the raw
 * token past the authentication boundary would widen the blast radius of
 * any accidental log-on-principal, expose bearer material to any filter
 * downstream, and violate the least-exposure posture in Rule 01. The
 * principal already carries every claim-derived field downstream code is
 * allowed to read.
 */
class MedcoreAuthenticationToken(
    private val medcorePrincipal: MedcorePrincipal,
) : AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_USER"))) {
    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): Any = medcorePrincipal
    override fun getCredentials(): Any = REDACTED_CREDENTIALS
    override fun getName(): String = medcorePrincipal.name

    companion object {
        const val REDACTED_CREDENTIALS: String = "[PROTECTED]"
    }
}
