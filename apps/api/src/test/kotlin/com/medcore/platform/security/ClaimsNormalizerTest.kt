package com.medcore.platform.security

import com.medcore.platform.config.MedcoreOidcProperties
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException

/**
 * Unit coverage for [ClaimsNormalizer] — Phase 3K.1, ADR-008 §2.
 *
 * In-memory JWT fixtures; no Spring context. Validates the strict
 * OIDC invariants Medcore enforces on every broker-issued token
 * before it builds a [MedcorePrincipal].
 */
class ClaimsNormalizerTest {

    private val oidcProperties = MedcoreOidcProperties(
        issuerUri = "https://workos.example.com/",
    )
    private val normalizer = ClaimsNormalizer(oidcProperties)

    @Test
    fun `happy path — standards-compliant OIDC token passes`() {
        val jwt = jwtWith(
            sub = "alice-subject",
            iss = "https://workos.example.com/",
            email = "alice@medcore.test",
            emailVerified = true,
        )
        assertThatCode { normalizer.validate(jwt) }.doesNotThrowAnyException()
    }

    @Test
    fun `token without email claim — passes (email is optional)`() {
        val jwt = jwtWith(
            sub = "alice-subject",
            iss = "https://workos.example.com/",
            email = null,
            emailVerified = null,
        )
        assertThatCode { normalizer.validate(jwt) }.doesNotThrowAnyException()
    }

    @Test
    fun `missing sub — rejected with InvalidBearerTokenException`() {
        val jwt = jwtWith(
            sub = null,
            iss = "https://workos.example.com/",
            email = null,
            emailVerified = null,
        )
        // Spring Security's InvalidBearerTokenException exposes the
        // upstream RFC 6750 "Invalid token" message on getMessage()
        // (intentional: the wire error should not leak details).
        // The internal description carries the actual diagnosis
        // which is accessible as getDescription(). Assertion holds
        // on type + description rather than getMessage().
        assertThatThrownBy { normalizer.validate(jwt) }
            .isInstanceOf(InvalidBearerTokenException::class.java)
            .satisfies({ ex ->
                val description = (ex as InvalidBearerTokenException).error.description
                assertThat(description).contains("'sub'")
            })
    }

    @Test
    fun `blank sub — rejected`() {
        val jwt = jwtWith(
            sub = "   ",
            iss = "https://workos.example.com/",
            email = null,
            emailVerified = null,
        )
        assertThatThrownBy { normalizer.validate(jwt) }
            .isInstanceOf(InvalidBearerTokenException::class.java)
    }

    @Test
    fun `email present but email_verified false — rejected (ADR-008 §2)`() {
        val jwt = jwtWith(
            sub = "alice-subject",
            iss = "https://workos.example.com/",
            email = "alice@medcore.test",
            emailVerified = false,
        )
        assertThatThrownBy { normalizer.validate(jwt) }
            .isInstanceOf(InvalidBearerTokenException::class.java)
            .satisfies({ ex ->
                val description = (ex as InvalidBearerTokenException).error.description
                assertThat(description).contains("email")
            })
    }

    @Test
    fun `email present but email_verified missing — rejected`() {
        val jwt = jwtWith(
            sub = "alice-subject",
            iss = "https://workos.example.com/",
            email = "alice@medcore.test",
            emailVerified = null,
        )
        assertThatThrownBy { normalizer.validate(jwt) }
            .isInstanceOf(InvalidBearerTokenException::class.java)
    }

    @Test
    fun `blank email — treated as absent, passes`() {
        // Corner case: broker emits email as empty string rather than
        // omitting the claim. Contract: treat as absent (no email
        // claim means no email-verified check needed).
        val jwt = jwtWith(
            sub = "alice-subject",
            iss = "https://workos.example.com/",
            email = "",
            emailVerified = null,
        )
        assertThatCode { normalizer.validate(jwt) }.doesNotThrowAnyException()
    }

    @Test
    fun `exception description carries no user-supplied values`() {
        // Rule 01: exception descriptions reach logs; they must not
        // carry user-supplied values. The normalizer's descriptions
        // reference claim NAMES only.
        val jwt = jwtWith(
            sub = null,
            iss = "https://workos.example.com/",
            email = null,
            emailVerified = null,
        )
        try {
            normalizer.validate(jwt)
        } catch (ex: InvalidBearerTokenException) {
            val description = ex.error.description
            assertThat(description).contains("'sub'")
            // No issuer values, no email values, no subject values.
            assertThat(description).doesNotContain("https://")
            assertThat(description).doesNotContain("@")
        }
    }

    private fun jwtWith(
        sub: String?,
        iss: String,
        email: String?,
        emailVerified: Boolean?,
    ): Jwt {
        val claims = buildMap<String, Any> {
            if (sub != null) put("sub", sub)
            put("iss", iss)
            if (email != null) put("email", email)
            if (emailVerified != null) put("email_verified", emailVerified)
        }
        return Jwt.withTokenValue("test-token")
            .header("alg", "RS256")
            .claims { it.putAll(claims) }
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build()
    }
}
