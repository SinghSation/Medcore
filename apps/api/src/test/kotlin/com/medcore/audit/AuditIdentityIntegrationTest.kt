package com.medcore.audit

import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import java.util.UUID
import javax.sql.DataSource
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
class AuditIdentityIntegrationTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var mockOAuth2Server: MockOAuth2Server

    @Autowired
    lateinit var dataSource: DataSource

    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun reset() {
        jdbc = JdbcTemplate(dataSource)
        jdbc.update("DELETE FROM audit.audit_event")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `first me call emits identity provisioned and login success in one transaction`() {
        val subject = "audit-${UUID.randomUUID()}"
        val response = getMe(subject)
        assertEquals(HttpStatus.OK, response.statusCode)

        val rows = auditRowsFor(action = "identity.user.provisioned")
        assertEquals(1, rows.size, "exactly one provisioned event on first sign-in")
        val provisioned = rows.single()
        assertEquals("SUCCESS", provisioned.outcome)
        assertEquals("USER", provisioned.actorType)
        assertNotNull(provisioned.actorId)
        assertEquals("identity.user", provisioned.resourceType)
        assertEquals(provisioned.actorId, provisioned.resourceId)

        val login = auditRowsFor(action = "identity.user.login.success")
        assertEquals(1, login.size, "exactly one login.success on first sign-in")
        assertEquals(provisioned.actorId, login.single().actorId)
    }

    @Test
    fun `second me call emits only login success (no duplicate provisioned)`() {
        val subject = "audit-${UUID.randomUUID()}"
        assertEquals(HttpStatus.OK, getMe(subject).statusCode)
        assertEquals(HttpStatus.OK, getMe(subject).statusCode)

        assertEquals(
            1,
            auditRowsFor(action = "identity.user.provisioned").size,
            "provisioned MUST NOT be re-emitted on repeat login",
        )
        assertEquals(
            2,
            auditRowsFor(action = "identity.user.login.success").size,
            "login.success MUST fire on every successful resolution",
        )
    }

    @Test
    fun `invalid bearer token emits identity login failure with null actor`() {
        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(
                HttpHeaders().apply {
                    set(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
                },
            ),
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)

        val rows = auditRowsFor(action = "identity.user.login.failure")
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("DENIED", row.outcome)
        assertEquals("USER", row.actorType)
        assertNull(row.actorId, "failed auth has no internal user yet")
        assertEquals("invalid_bearer_token", row.reason)
    }

    @Test
    fun `unauthenticated request with no bearer token does not emit login failure`() {
        val response = rest.getForEntity("/api/v1/me", String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertEquals(
            0,
            auditRowsFor(action = "identity.user.login.failure").size,
            "anonymous probes MUST NOT generate login.failure rows",
        )
    }

    @Test
    fun `audit rows never persist user email or display name`() {
        val subject = "audit-${UUID.randomUUID()}"
        assertEquals(HttpStatus.OK, getMe(subject).statusCode)

        val email = "$subject@medcore.test"
        val displayName = "Display $subject"
        val preferredUsername = "$subject-user"
        val forbidden = listOf(email, displayName, preferredUsername, subject)

        // Query raw columns to prove no value we consider PII has been smuggled in.
        val rows = jdbc.queryForList(
            """
            SELECT action, actor_display, resource_type, resource_id, reason
              FROM audit.audit_event
            """.trimIndent(),
        )
        rows.forEach { row ->
            forbidden.forEach { needle ->
                row.values.filterIsInstance<String>().forEach { value ->
                    assertTrue(
                        !value.contains(needle),
                        "audit row for ${row["action"]} unexpectedly contained $needle: $value",
                    )
                }
            }
        }
    }

    private fun getMe(subject: String) = rest.exchange(
        "/api/v1/me",
        HttpMethod.GET,
        HttpEntity<Void>(bearer(tokenFor(subject))),
        String::class.java,
    )

    private fun tokenFor(subject: String): String =
        mockOAuth2Server.issueToken(
            issuerId = MOCK_ISSUER_ID,
            clientId = "medcore-test-client",
            tokenCallback = DefaultOAuth2TokenCallback(
                issuerId = MOCK_ISSUER_ID,
                subject = subject,
                claims = mapOf(
                    "email" to "$subject@medcore.test",
                    "email_verified" to true,
                    "preferred_username" to "$subject-user",
                    "name" to "Display $subject",
                ),
            ),
        ).serialize()

    private fun bearer(token: String) = HttpHeaders().apply {
        set(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    private data class AuditRow(
        val action: String,
        val outcome: String,
        val actorType: String,
        val actorId: String?,
        val tenantId: String?,
        val resourceType: String?,
        val resourceId: String?,
        val reason: String?,
    )

    private fun auditRowsFor(action: String): List<AuditRow> =
        jdbc.query(
            """
            SELECT action, outcome, actor_type,
                   actor_id::text  AS actor_id,
                   tenant_id::text AS tenant_id,
                   resource_type, resource_id, reason
              FROM audit.audit_event
             WHERE action = ?
             ORDER BY recorded_at
            """.trimIndent(),
            { rs, _ ->
                AuditRow(
                    action = rs.getString("action"),
                    outcome = rs.getString("outcome"),
                    actorType = rs.getString("actor_type"),
                    actorId = rs.getString("actor_id"),
                    tenantId = rs.getString("tenant_id"),
                    resourceType = rs.getString("resource_type"),
                    resourceId = rs.getString("resource_id"),
                    reason = rs.getString("reason"),
                )
            },
            action,
        )
}
