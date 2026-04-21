package com.medcore.platform.config

import java.net.URI
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.env.Environment

/**
 * ADR-002 §2 guardrail — fail fast when the `prod` profile is active AND the
 * configured OIDC issuer points at a development identity provider.
 *
 * Runs before the ApplicationContext starts so a misconfigured deployment
 * never serves a single request against a mock IdP.
 *
 * Implemented as an [ApplicationListener] bound via `spring.factories`
 * (see META-INF/spring.factories) rather than a bean, because it MUST fire
 * before the context (and therefore before bean binding) is built.
 */
class ProdProfileOidcGuard : ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    override fun onApplicationEvent(event: ApplicationEnvironmentPreparedEvent) {
        val environment: Environment = event.environment
        if (!environment.activeProfiles.contains(PROD_PROFILE)) return

        val issuer = environment.getProperty(ISSUER_PROPERTY)
            ?: throw IllegalStateException(
                "medcore.oidc.issuer-uri is not set; production startup requires an OIDC issuer",
            )

        if (isForbiddenIssuer(issuer)) {
            throw IllegalStateException(
                "OIDC issuer '$issuer' is forbidden under the 'prod' profile (ADR-002 §2). " +
                    "Configure a production-grade issuer before starting in prod.",
            )
        }
    }

    companion object {
        const val PROD_PROFILE: String = "prod"
        const val ISSUER_PROPERTY: String = "medcore.oidc.issuer-uri"
        private val FORBIDDEN_HOST_TOKENS = listOf(
            "mock-oauth2-server",
            "localhost",
            "127.0.0.1",
            "::1",
        )

        internal fun isForbiddenIssuer(issuerUri: String): Boolean {
            val host = runCatching { URI(issuerUri).host?.lowercase() }.getOrNull()
                ?: return true
            return FORBIDDEN_HOST_TOKENS.any { token -> host == token || host.contains(token) }
        }
    }
}
