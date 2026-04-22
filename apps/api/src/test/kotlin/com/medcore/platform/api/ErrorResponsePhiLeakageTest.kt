package com.medcore.platform.api

import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.context.TestPropertySource

/**
 * Rule 01 operational control for Phase 3G error bodies. Fires each
 * error path and asserts the response body does NOT contain:
 *
 *   - stack-trace markers (`at `, `Caused by`),
 *   - framework exception class names (e.g. `IllegalStateException`,
 *     `NullPointerException`, `ConstraintViolationException`,
 *     `EntityNotFoundException`, `OptimisticLockingFailureException`),
 *   - SQL fragments (e.g. `SELECT `, `FROM `, `WHERE `, `INSERT `),
 *   - bearer-token values,
 *   - the raw exception message text that the test controller
 *     deliberately seeds with suspicious content
 *     (`something-internal SELECT * FROM patients WHERE id=123`).
 *
 * Future slices that add error-emission sites MUST extend this test
 * with coverage for the new sites.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "medcore.testing.error-paths-controller.enabled=true",
    ],
)
class ErrorResponsePhiLeakageTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var mockOAuth2Server: MockOAuth2Server

    @Test
    fun `500 body never contains stack traces sql class names or seeded phi tokens`() {
        // Controller throws IllegalStateException with the message
        // "something-internal SELECT * FROM patients WHERE id=123".
        // None of that text may reach the wire.
        val response = rest.exchange(
            "/api/test/errors/uncaught",
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertNoLeakage(response.body, extraForbidden = listOf(
            "IllegalStateException",
            "something-internal",
            "SELECT",
            "FROM patients",
            "id=123",
            "at com.medcore",
            "at org.springframework",
            "Caused by",
        ))
    }

    @Test
    fun `404 body never contains exception class names or paths`() {
        val response = rest.exchange(
            "/api/test/errors/entity-not-found",
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertNoLeakage(response.body, extraForbidden = listOf(
            "EntityNotFoundException",
            "jakarta.persistence",
            "at com.medcore",
        ))
    }

    @Test
    fun `409 body never contains exception class names`() {
        val response = rest.exchange(
            "/api/test/errors/optimistic-lock",
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertNoLeakage(response.body, extraForbidden = listOf(
            "OptimisticLockingFailureException",
            "org.springframework",
            "at com.medcore",
        ))
    }

    @Test
    fun `422 body never contains rejected values`() {
        // Body carries a deliberately sensitive-looking value we must
        // NOT echo back. Only field names + constraint codes may
        // appear in the response.
        val phiLike = "patient-dob-1970-01-01"
        val headers = authedHeaders().apply {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
        }
        val response = rest.exchange(
            "/api/test/errors/body-validation",
            HttpMethod.POST,
            HttpEntity("""{"name":"$phiLike","amount":0}""", headers),
            String::class.java,
        )
        assertNoLeakage(response.body, extraForbidden = listOf(
            phiLike,
            "MethodArgumentNotValidException",
            "at com.medcore",
        ))
    }

    @Test
    fun `401 body never contains token or issuer value`() {
        val token = issueToken("leak-401")
        val headers = HttpHeaders().apply { setBearerAuth("tampered-$token") }
        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )
        assertNoLeakage(response.body, extraForbidden = listOf(
            token,
            "tampered-",
            "BearerTokenAuthenticationEntryPoint",
            "AuthenticationException",
            "at com.medcore",
        ))
    }

    private fun assertNoLeakage(body: String?, extraForbidden: List<String>) {
        assertThat(body).describedAs("response body must not be null").isNotNull()
        val forbidden = baseForbidden() + extraForbidden
        forbidden.forEach { needle ->
            assertThat(body!!)
                .describedAs("error body must not contain `$needle`")
                .doesNotContain(needle)
        }
    }

    private fun baseForbidden(): List<String> = listOf(
        // Generic leakage patterns that should never appear in any
        // error body regardless of code path.
        "at java.base",
        "java.lang.",
        "org.hibernate",
        "org.postgresql",
    )

    private fun authedHeaders(): HttpHeaders =
        HttpHeaders().apply { setBearerAuth(issueToken("phi-test")) }

    private fun authed(): HttpEntity<Void> = HttpEntity(authedHeaders())

    private fun issueToken(subjectPrefix: String): String =
        mockOAuth2Server.issueToken(
            issuerId = MOCK_ISSUER_ID,
            clientId = "medcore-test-client",
            tokenCallback = DefaultOAuth2TokenCallback(
                issuerId = MOCK_ISSUER_ID,
                subject = "$subjectPrefix-${java.util.UUID.randomUUID()}",
                claims = mapOf("email_verified" to true),
            ),
        ).serialize()
}
