package com.medcore

import no.nav.security.mock.oauth2.MockOAuth2Server
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Shared test infrastructure:
 *
 * - Testcontainers-backed PostgreSQL (ADR-001 §6).
 * - In-process `mock-oauth2-server` (ADR-002). Exposes a real JWKS endpoint
 *   that Spring Security's resource server validates against, so tests hit
 *   the actual token pipeline — no Spring Security internals are mocked.
 *
 * The mock server's issuer URI is injected into the Spring environment via
 * [DynamicPropertyRegistrar] so `medcore.oidc.issuer-uri` resolves BEFORE
 * the `JwtDecoder` bean is created (which performs OIDC discovery eagerly).
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16.4")

    @Bean(destroyMethod = "shutdown")
    fun mockOAuth2Server(): MockOAuth2Server {
        val server = MockOAuth2Server()
        server.start()
        return server
    }

    @Bean
    fun mockOAuth2ServerPropertyRegistrar(server: MockOAuth2Server): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("medcore.oidc.issuer-uri") {
                server.issuerUrl(MOCK_ISSUER_ID).toString()
            }
        }

    companion object {
        const val MOCK_ISSUER_ID: String = "medcore-test"
    }
}
