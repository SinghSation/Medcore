package com.medcore.platform.config

import jakarta.validation.constraints.NotBlank
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Typed platform configuration for OIDC resource-server validation.
 *
 * Binds `medcore.oidc.*`. Consumers MUST inject this class rather than reading
 * raw properties via `@Value`; that is a non-negotiable governance rule
 * (AGENTS.md §3.1, Rule 01 — centralized authentication).
 *
 * ADR-002 §2 guardrail: production startup MUST reject any issuer that targets
 * `mock-oauth2-server` or `localhost`. The guardrail itself lives in
 * [ProdProfileOidcGuard]; this class remains configuration-only.
 */
@Validated
@ConfigurationProperties(prefix = "medcore.oidc")
class MedcoreOidcProperties(
    /**
     * OIDC issuer URI. The resource server discovers JWKS, `iss`, and metadata
     * from `${issuerUri}/.well-known/openid-configuration`. Exact-match against
     * the JWT `iss` claim is enforced by Spring Security.
     */
    @field:NotBlank
    val issuerUri: String,

    /**
     * Optional audience claim that inbound tokens MUST carry. When null, the
     * resource server does not additionally validate `aud` beyond the issuer
     * check. Set this once the production IdP is chosen and an audience is
     * agreed (tracked in follow-up ADR).
     */
    val audience: String? = null,

    /**
     * Allowed clock skew between the token's `iat`/`exp`/`nbf` and the
     * resource server's clock. Kept tight by default; production IdPs with
     * cross-region clock drift MAY raise this with justification.
     */
    val clockSkew: Duration = Duration.ofSeconds(30),

    /**
     * Standard OIDC claims the principal converter reads. These names are
     * spec-standard today; expressing them as configuration preserves the
     * ability to switch IdPs without code changes (ADR-002 §4.3).
     */
    val claims: ClaimNames = ClaimNames(),
) {
    /**
     * Well-known OIDC claim names. Defaults reflect RFC 7519 + OIDC Core 1.0;
     * overrides are permitted only when the production IdP justifies drift.
     */
    class ClaimNames(
        val subject: String = "sub",
        val email: String = "email",
        val emailVerified: String = "email_verified",
        val preferredUsername: String = "preferred_username",
        val name: String = "name",
    )
}
