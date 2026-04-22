package com.medcore.platform.api

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource

/**
 * Behavioural verification of [GlobalExceptionHandler] across every
 * status class the Phase 3G plan commits to:
 *
 *   401, 403, 404, 409, 422, 500.
 *
 * The companion controller [ErrorPathsTestController] forces each
 * exception path; the opt-in property below mounts that controller
 * on this test class only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "medcore.testing.error-paths-controller.enabled=true",
    ],
)
class GlobalExceptionHandlerIntegrationTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var mockOAuth2Server: MockOAuth2Server

    @Autowired
    lateinit var mapper: ObjectMapper

    // ---------- 401 ----------

    @Test
    fun `unauthenticated request returns unified 401 envelope`() {
        val response = rest.getForEntity("/api/v1/me", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertJsonContentType(response.headers.contentType)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.AUTH_UNAUTHENTICATED)
        assertThat(body.message).isEqualTo("Authentication required.")
        assertThat(body.requestId).isNotBlank()
    }

    @Test
    fun `invalid bearer token returns unified 401 envelope`() {
        val headers = HttpHeaders().apply { setBearerAuth("not-a-real-token") }
        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.AUTH_UNAUTHENTICATED)
        assertThat(body.message).isEqualTo("Authentication required.")
    }

    // ---------- 403 ----------

    @Test
    fun `access-denied via denyAll returns unified 403 envelope`() {
        val response = rest.exchange(
            "/api/test/errors/access-denied",
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertJsonContentType(response.headers.contentType)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.AUTH_FORBIDDEN)
        assertThat(body.message).isEqualTo("Access denied.")
        assertThat(body.requestId).isNotBlank()
    }

    // ---------- 404 ----------

    @Test
    fun `EntityNotFoundException returns 404 resource_not_found`() {
        val response = rest.exchange(
            "/api/test/errors/entity-not-found",
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `EmptyResultDataAccessException returns 404 resource_not_found`() {
        val response = rest.exchange(
            "/api/test/errors/empty-result",
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `no such route returns 404 resource_not_found`() {
        val response = rest.exchange(
            "/api/definitely-does-not-exist-" + System.currentTimeMillis(),
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.RESOURCE_NOT_FOUND)
    }

    // ---------- 409 ----------

    @Test
    fun `optimistic lock returns 409 resource_conflict`() {
        val response = rest.exchange(
            "/api/test/errors/optimistic-lock",
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.RESOURCE_CONFLICT)
    }

    @Test
    fun `unique violation returns 409 resource_conflict`() {
        val response = rest.exchange(
            "/api/test/errors/unique-violation",
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.RESOURCE_CONFLICT)
    }

    @Test
    fun `foreign-key violation returns 409 resource_conflict`() {
        val response = rest.exchange(
            "/api/test/errors/fk-violation",
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.RESOURCE_CONFLICT)
    }

    // ---------- 422 ----------

    @Test
    fun `body validation failure returns 422 with field-name-only details`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }
        val authHeaders = authedHeaders()
        authHeaders.addAll(headers)

        val response = rest.exchange(
            "/api/test/errors/body-validation",
            HttpMethod.POST,
            HttpEntity("""{"name":"","amount":0}""", authHeaders),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.REQUEST_VALIDATION_FAILED)

        @Suppress("UNCHECKED_CAST")
        val errors = body.details?.get("validationErrors") as? List<Map<String, Any>>
        assertThat(errors)
            .describedAs("validation errors must be a flat list")
            .isNotNull()
            .isNotEmpty()
        errors!!.forEach { err ->
            assertThat(err.keys)
                .describedAs("each validation error must expose only {field, code} — no values")
                .containsExactlyInAnyOrder("field", "code")
        }
    }

    @Test
    fun `path validation failure returns 422`() {
        val response = rest.exchange(
            "/api/test/errors/path-validation?n=-5",
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.REQUEST_VALIDATION_FAILED)
    }

    @Test
    fun `not-null violation returns 422 request_validation_failed`() {
        val response = rest.exchange(
            "/api/test/errors/not-null-violation",
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.REQUEST_VALIDATION_FAILED)
    }

    @Test
    fun `check-constraint violation returns 422 request_validation_failed`() {
        val response = rest.exchange(
            "/api/test/errors/check-violation",
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.REQUEST_VALIDATION_FAILED)
    }

    @Test
    fun `TenantContextMissingException returns 422 tenancy_context_required`() {
        val response = rest.exchange(
            "/api/test/errors/tenant-context-missing",
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.TENANCY_CONTEXT_REQUIRED)
        assertThat(body.requestId).isNotBlank()
    }

    // ---------- 500 ----------

    @Test
    fun `uncaught exception returns 500 server_error`() {
        val response = rest.exchange(
            "/api/test/errors/uncaught",
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.SERVER_ERROR)
        assertThat(body.message).isEqualTo("An unexpected error occurred.")
    }

    @Test
    fun `unrecognised SQLSTATE on data integrity maps to 500`() {
        val response = rest.exchange(
            "/api/test/errors/unknown-data-integrity",
            HttpMethod.GET,
            authed(),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        val body = parse(response.body)
        assertThat(body.code).isEqualTo(ErrorCodes.SERVER_ERROR)
    }

    // ---------- helpers ----------

    private fun authedHeaders(): HttpHeaders =
        HttpHeaders().apply { setBearerAuth(issueToken()) }

    private fun authed(): HttpEntity<Void> = HttpEntity(authedHeaders())

    private fun issueToken(): String =
        mockOAuth2Server.issueToken(
            issuerId = MOCK_ISSUER_ID,
            clientId = "medcore-test-client",
            tokenCallback = DefaultOAuth2TokenCallback(
                issuerId = MOCK_ISSUER_ID,
                subject = "err-${java.util.UUID.randomUUID()}",
                claims = mapOf("email_verified" to true),
            ),
        ).serialize()

    private fun parse(body: String?): ErrorResponse =
        mapper.readValue(body, ErrorResponse::class.java)

    private fun assertJsonContentType(contentType: MediaType?) {
        assertThat(contentType).isNotNull()
        assertThat(contentType!!.type).isEqualTo("application")
        assertThat(contentType.subtype).contains("json")
    }
}
