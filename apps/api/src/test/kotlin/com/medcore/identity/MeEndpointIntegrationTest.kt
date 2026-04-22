package com.medcore.identity

import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import com.medcore.identity.api.MeStatus
import java.util.UUID
import javax.sql.DataSource
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class MeEndpointIntegrationTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var mockOAuth2Server: MockOAuth2Server

    @Autowired
    @Qualifier("adminDataSource")
    lateinit var dataSource: DataSource

    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun resetIdentity() {
        jdbc = JdbcTemplate(dataSource)
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `GET me without bearer token returns 401`() {
        val response = rest.getForEntity("/api/v1/me", String::class.java)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertTrue(
            response.headers[HttpHeaders.WWW_AUTHENTICATE]
                ?.any { it.contains("Bearer", ignoreCase = true) } == true,
            "401 response MUST include a WWW-Authenticate: Bearer challenge",
        )
    }

    @Test
    fun `GET me with valid bearer token returns the caller identity and JIT-provisions`() {
        val subject = "user-${UUID.randomUUID()}"
        val token = issueToken(
            subject = subject,
            email = "dev+${subject}@medcore.test",
            emailVerified = true,
            preferredUsername = "dev-$subject",
            name = "Dev User $subject",
        )

        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(token)),
            MeJsonProjection::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        val body = response.body!!
        assertEquals(subject, body.subject)
        assertEquals(mockOAuth2Server.issuerUrl(MOCK_ISSUER_ID).toString(), body.issuer)
        assertEquals("dev+${subject}@medcore.test", body.email)
        assertEquals(true, body.emailVerified)
        assertEquals("dev-$subject", body.preferredUsername)
        assertEquals("Dev User $subject", body.displayName)
        assertEquals(MeStatus.ACTIVE.name, body.status)
        UUID.fromString(body.userId) // throws if not a UUID

        assertEquals(
            1,
            countIdentityUsersForSubject(subject),
            "JIT provisioning MUST insert exactly one identity.user row for the (issuer, subject) pair",
        )
    }

    @Test
    fun `repeated GET me for the same subject is idempotent`() {
        val subject = "user-${UUID.randomUUID()}"
        val token = issueToken(subject = subject)

        val first = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(token)),
            MeJsonProjection::class.java,
        )
        val second = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(token)),
            MeJsonProjection::class.java,
        )

        assertEquals(HttpStatus.OK, first.statusCode)
        assertEquals(HttpStatus.OK, second.statusCode)
        assertEquals(first.body?.userId, second.body?.userId)
        assertEquals(
            1,
            countIdentityUsersForSubject(subject),
            "Repeated sign-in for the same (issuer, subject) MUST NOT duplicate the identity row",
        )
    }

    @Test
    fun `response shape matches the OpenAPI contract — no excess fields, only declared keys`() {
        val subject = "user-${UUID.randomUUID()}"
        val token = issueToken(subject = subject)

        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(token)),
            Map::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        val keys = response.body?.keys?.map { it as String }?.toSet() ?: emptySet()
        val expected = setOf(
            "userId",
            "issuer",
            "subject",
            "email",
            "emailVerified",
            "displayName",
            "preferredUsername",
            "status",
        )
        assertEquals(
            expected,
            keys,
            "Response MUST contain exactly the keys declared in packages/schemas/openapi/identity/identity.yaml",
        )
    }

    private fun issueToken(
        subject: String,
        email: String? = "dev+${subject}@medcore.test",
        emailVerified: Boolean = true,
        preferredUsername: String? = "dev-$subject",
        name: String? = "Dev User $subject",
    ): String {
        val claims = buildMap<String, Any> {
            email?.let { put("email", it) }
            put("email_verified", emailVerified)
            preferredUsername?.let { put("preferred_username", it) }
            name?.let { put("name", it) }
        }
        return mockOAuth2Server.issueToken(
            issuerId = MOCK_ISSUER_ID,
            clientId = "medcore-test-client",
            tokenCallback = DefaultOAuth2TokenCallback(
                issuerId = MOCK_ISSUER_ID,
                subject = subject,
                claims = claims,
            ),
        ).serialize()
    }

    private fun bearer(token: String): HttpHeaders = HttpHeaders().apply {
        set(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    private fun countIdentityUsersForSubject(subject: String): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM identity.\"user\" WHERE subject = ?",
            Int::class.java,
            subject,
        ) ?: 0

    /**
     * Deliberately ignores unknown fields so a schema addition would surface
     * through the dedicated shape test above, not here.
     */
    data class MeJsonProjection(
        val userId: String = "",
        val issuer: String = "",
        val subject: String = "",
        val email: String? = null,
        val emailVerified: Boolean = false,
        val displayName: String? = null,
        val preferredUsername: String? = null,
        val status: String = "",
    )
}
