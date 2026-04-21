package com.medcore.platform.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultBootstrapContext
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.mock.env.MockEnvironment

/**
 * Unit tests for the ADR-002 §2 production-profile guardrail.
 *
 * These tests bypass booting a full application context — they invoke the
 * listener directly with a synthesised [ApplicationEnvironmentPreparedEvent].
 * That is the right layer: the guard's contract is "examine the environment,
 * throw if misconfigured under `prod`," and that is what is exercised here.
 */
class ProdProfileOidcGuardTest {

    private val guard = ProdProfileOidcGuard()

    @Test
    fun `prod profile with localhost issuer fails startup`() {
        val env = MockEnvironment().apply {
            setActiveProfiles(ProdProfileOidcGuard.PROD_PROFILE)
            setProperty(ProdProfileOidcGuard.ISSUER_PROPERTY, "http://localhost:8888/default")
        }
        val event = ApplicationEnvironmentPreparedEvent(
            DefaultBootstrapContext(),
            SpringApplication(),
            arrayOf(),
            env,
        )

        val ex = assertThrows(IllegalStateException::class.java) {
            guard.onApplicationEvent(event)
        }
        assertTrue(ex.message!!.contains("forbidden under the 'prod' profile"))
    }

    @Test
    fun `prod profile with mock-oauth2-server issuer fails startup`() {
        val env = MockEnvironment().apply {
            setActiveProfiles(ProdProfileOidcGuard.PROD_PROFILE)
            setProperty(
                ProdProfileOidcGuard.ISSUER_PROPERTY,
                "http://mock-oauth2-server:8888/default",
            )
        }
        val event = ApplicationEnvironmentPreparedEvent(
            DefaultBootstrapContext(),
            SpringApplication(),
            arrayOf(),
            env,
        )

        assertThrows(IllegalStateException::class.java) { guard.onApplicationEvent(event) }
    }

    @Test
    fun `prod profile with missing issuer fails startup`() {
        val env = MockEnvironment().apply {
            setActiveProfiles(ProdProfileOidcGuard.PROD_PROFILE)
        }
        val event = ApplicationEnvironmentPreparedEvent(
            DefaultBootstrapContext(),
            SpringApplication(),
            arrayOf(),
            env,
        )

        val ex = assertThrows(IllegalStateException::class.java) {
            guard.onApplicationEvent(event)
        }
        assertTrue(ex.message!!.contains("is not set"))
    }

    @Test
    fun `prod profile with production issuer succeeds`() {
        val env = MockEnvironment().apply {
            setActiveProfiles(ProdProfileOidcGuard.PROD_PROFILE)
            setProperty(
                ProdProfileOidcGuard.ISSUER_PROPERTY,
                "https://auth.example-idp.com/realms/medcore",
            )
        }
        val event = ApplicationEnvironmentPreparedEvent(
            DefaultBootstrapContext(),
            SpringApplication(),
            arrayOf(),
            env,
        )

        // should not throw
        guard.onApplicationEvent(event)
    }

    @Test
    fun `non-prod profile with localhost issuer is allowed`() {
        val env = MockEnvironment().apply {
            setProperty(ProdProfileOidcGuard.ISSUER_PROPERTY, "http://localhost:8888/default")
        }
        val event = ApplicationEnvironmentPreparedEvent(
            DefaultBootstrapContext(),
            SpringApplication(),
            arrayOf(),
            env,
        )

        // no prod profile, no throw
        guard.onApplicationEvent(event)
    }

    @Test
    fun `isForbiddenIssuer recognises loopback, mock, and empty hosts`() {
        assertTrue(ProdProfileOidcGuard.isForbiddenIssuer("http://localhost/default"))
        assertTrue(ProdProfileOidcGuard.isForbiddenIssuer("http://127.0.0.1:8888/default"))
        assertTrue(ProdProfileOidcGuard.isForbiddenIssuer("http://mock-oauth2-server:8888/default"))
        assertTrue(ProdProfileOidcGuard.isForbiddenIssuer("not-a-url"))
        assertFalse(ProdProfileOidcGuard.isForbiddenIssuer("https://auth.example-idp.com/realms/medcore"))
        assertEquals(false, ProdProfileOidcGuard.isForbiddenIssuer("https://keycloak.internal/realms/medcore"))
    }
}
