package com.medcore.platform.security

import com.medcore.platform.config.MedcoreOidcProperties
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.stereotype.Component

/**
 * Strict OIDC claim validator for inbound JWTs (Phase 3K.1, ADR-008).
 *
 * ### Role in the identity architecture
 *
 * ADR-008 positions the production IdP (WorkOS) as an **identity
 * broker / orchestration layer**, NOT as Medcore's system of
 * record for user lifecycle state. Medcore owns
 * `identity.user.status`, tenant membership, and audit linkage;
 * the broker routes authentication, federates enterprise SSO
 * connections, and emits OIDC-compliant tokens.
 *
 * A consequence of that posture: every inbound JWT is treated as
 * **external data** that must pass explicit Medcore-side
 * validation before the platform builds a [MedcorePrincipal] from
 * it. This validator runs at the converter boundary and fails
 * loudly ([InvalidBearerTokenException] → 401) if any of the
 * invariants Medcore requires are not met.
 *
 * ### Invariants enforced here
 *
 * - Subject claim (`sub`) present and non-blank.
 * - Issuer claim (`iss`) present and non-blank.
 * - `email_verified` is explicitly `true` when an `email` claim is
 *   present. Silent acceptance of unverified emails would let a
 *   compromised broker forward attacker-controlled identities
 *   into Medcore's `identity.user` table.
 *
 * ### Invariants NOT enforced here (Spring Security handles them)
 *
 * - Signature verification — performed by the configured
 *   `JwtDecoder`.
 * - `iss` value matches the configured issuer URI — validator set
 *   in `SecurityConfig`.
 * - `aud` matches configured audience (when audience is set).
 * - Time windows (`exp`, `nbf`, `iat`).
 *
 * ### Vendor-swap insurance
 *
 * This class is the seam where vendor-specific claim remapping
 * would land IF a future slice swaps WorkOS for a non-OIDC-compliant
 * vendor (e.g., AWS Cognito's `cognito:groups` → `groups`). The
 * current implementation does zero remapping — WorkOS issues
 * standards-compliant OIDC tokens, so the validator is the whole
 * story. A vendor swap would introduce a second implementation of
 * this class or a vendor-specific delegate, NOT rewiring through
 * every caller.
 *
 * ### Why not duplicate Spring Security's validation?
 *
 * Spring's `JwtDecoder` already validates signature + iss + aud +
 * time windows. Duplicating that here would be a trust boundary
 * violation (two layers claiming responsibility for the same
 * check). This validator picks up what Spring's decoder does
 * NOT cover: subject-presence, issuer-presence post-decoder, and
 * the OIDC-specific `email_verified` contract.
 */
@Component
class ClaimsNormalizer(
    private val oidcProperties: MedcoreOidcProperties,
) {

    /**
     * Throws [InvalidBearerTokenException] if [jwt] does not meet
     * Medcore's strict OIDC invariants. Spring Security maps the
     * exception to 401 via the standard resource-server entry
     * point; Medcore's [AuditingAuthenticationEntryPoint] then
     * emits `identity.user.login.failure` with
     * `reason = invalid_bearer_token`.
     */
    fun validate(jwt: Jwt) {
        val claims = oidcProperties.claims

        val subject = jwt.getClaimAsString(claims.subject)
        if (subject.isNullOrBlank()) {
            throw InvalidBearerTokenException(
                "JWT is missing or has a blank '${claims.subject}' claim",
            )
        }

        val issuer = jwt.issuer?.toString()
        if (issuer.isNullOrBlank()) {
            throw InvalidBearerTokenException("JWT is missing the 'iss' claim")
        }

        val email = jwt.getClaimAsString(claims.email)
        if (!email.isNullOrBlank()) {
            val verified = jwt.getClaim<Boolean>(claims.emailVerified) ?: false
            if (!verified) {
                // RFC 6750 restricts the error_description to
                // printable ASCII (%x20-7E except " and \), so no
                // em-dash or smart-punctuation in this string.
                throw InvalidBearerTokenException(
                    "JWT '${claims.email}' claim is present but " +
                        "'${claims.emailVerified}' is not true - Medcore " +
                        "refuses unverified email identities (ADR-008).",
                )
            }
        }
    }
}
