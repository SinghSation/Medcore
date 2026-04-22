package com.medcore.platform.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import java.util.UUID
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
 * Asserts response header `X-Request-Id` matches `ErrorResponse.requestId`
 * in the body, for every error code path Phase 3G owns.
 *
 * Header/body parity is the contract that makes correlation useful:
 * a caller looking at a response body must be able to search logs
 * by `requestId` and find the exact request. If header and body drift,
 * the correlation is worse than no correlation at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "medcore.testing.error-paths-controller.enabled=true",
    ],
)
class HeaderBodyRequestIdParityTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var mockOAuth2Server: MockOAuth2Server

    @Autowired
    lateinit var mapper: ObjectMapper

    @Test
    fun `parity on 401 unauthenticated`() {
        assertParity(path = "/api/v1/me", bearer = null)
    }

    @Test
    fun `parity on 403 access-denied`() {
        assertParity(path = "/api/test/errors/access-denied", bearer = issueToken())
    }

    @Test
    fun `parity on 404 not found`() {
        assertParity(path = "/api/test/errors/entity-not-found", bearer = issueToken())
    }

    @Test
    fun `parity on 409 conflict`() {
        assertParity(path = "/api/test/errors/optimistic-lock", bearer = issueToken())
    }

    @Test
    fun `parity on 422 tenant-context-missing`() {
        assertParity(path = "/api/test/errors/tenant-context-missing", bearer = issueToken())
    }

    @Test
    fun `parity on 500 uncaught`() {
        assertParity(path = "/api/test/errors/uncaught", bearer = issueToken())
    }

    @Test
    fun `parity when client supplies a valid inbound request id`() {
        val inbound = UUID.randomUUID().toString()
        val headers = HttpHeaders().apply {
            set("X-Request-Id", inbound)
            setBearerAuth(issueToken())
        }
        val response = rest.exchange(
            "/api/test/errors/uncaught",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )
        val headerId = response.headers.getFirst("X-Request-Id")
        val bodyId = mapper.readValue(response.body, ErrorResponse::class.java).requestId
        assertThat(headerId).describedAs("response header").isEqualTo(inbound)
        assertThat(bodyId).describedAs("body requestId").isEqualTo(inbound)
    }

    private fun assertParity(path: String, bearer: String?) {
        val headers = HttpHeaders().apply { bearer?.let { setBearerAuth(it) } }
        val response = rest.exchange(
            path,
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )
        val headerId = response.headers.getFirst("X-Request-Id")
        val bodyId = mapper.readValue(response.body, ErrorResponse::class.java).requestId
        assertThat(headerId).describedAs("response header for $path").isNotBlank()
        assertThat(bodyId).describedAs("body requestId for $path").isNotBlank()
        assertThat(bodyId).describedAs("header and body requestId must match for $path").isEqualTo(headerId)
    }

    private fun issueToken(): String =
        mockOAuth2Server.issueToken(
            issuerId = MOCK_ISSUER_ID,
            clientId = "medcore-test-client",
            tokenCallback = DefaultOAuth2TokenCallback(
                issuerId = MOCK_ISSUER_ID,
                subject = "parity-${UUID.randomUUID()}",
                claims = mapOf("email_verified" to true),
            ),
        ).serialize()
}
