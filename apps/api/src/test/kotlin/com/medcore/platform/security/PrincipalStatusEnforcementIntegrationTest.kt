package com.medcore.platform.security

import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import java.util.UUID
import javax.sql.DataSource
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
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

/**
 * Integration coverage for Medcore-authoritative
 * [PrincipalStatus] enforcement at the auth boundary
 * (Phase 3K.1, ADR-008 §2.6).
 *
 * Proves the invariant: a cryptographically-valid JWT (mock IdP
 * issues a clean token for subject "alice") is REJECTED with 401
 * when `identity.user.status` for the mapped user is DISABLED
 * or DELETED. The IdP "says" the user is fine; Medcore says
 * otherwise and wins.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class PrincipalStatusEnforcementIntegrationTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var mockOAuth2Server: MockOAuth2Server

    @Autowired
    @Qualifier("adminDataSource")
    lateinit var dataSource: DataSource

    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun reset() {
        jdbc = JdbcTemplate(dataSource)
        jdbc.update("DELETE FROM audit.audit_event")
        jdbc.update("DELETE FROM clinical.patient_identifier")
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `ACTIVE user authenticates successfully — baseline`() {
        // JIT-provisioning on first call.
        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `DISABLED user is rejected with 401 — Medcore status overrides valid token`() {
        // First request provisions the user as ACTIVE.
        rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            Map::class.java,
        )
        // Admin disables the user directly in the DB (simulates
        // a Medcore-side action like termination).
        val userId = jdbc.queryForObject(
            "SELECT id FROM identity.\"user\" WHERE subject = 'alice'",
            UUID::class.java,
        )!!
        jdbc.update("UPDATE identity.\"user\" SET status = 'DISABLED' WHERE id = ?", userId)

        // Second request with the SAME (cryptographically-valid) token
        // must now be rejected. IdP still says the user exists; Medcore
        // says DISABLED → 401.
        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        // Audit row carries the specific denial reason + actor id.
        val auditRow = jdbc.queryForMap(
            """
            SELECT action, reason, actor_id
              FROM audit.audit_event
             WHERE action = 'identity.user.login.failure'
             ORDER BY recorded_at DESC
             LIMIT 1
            """.trimIndent(),
        )
        assertThat(auditRow["reason"]).isEqualTo("principal_disabled")
        assertThat(auditRow["actor_id"]).isEqualTo(userId)
    }

    @Test
    fun `DELETED user is rejected with 401 — reason=principal_deleted`() {
        rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            Map::class.java,
        )
        val userId = jdbc.queryForObject(
            "SELECT id FROM identity.\"user\" WHERE subject = 'alice'",
            UUID::class.java,
        )!!
        jdbc.update("UPDATE identity.\"user\" SET status = 'DELETED' WHERE id = ?", userId)

        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        val auditRow = jdbc.queryForMap(
            """
            SELECT reason, actor_id
              FROM audit.audit_event
             WHERE action = 'identity.user.login.failure'
             ORDER BY recorded_at DESC
             LIMIT 1
            """.trimIndent(),
        )
        assertThat(auditRow["reason"]).isEqualTo("principal_deleted")
        assertThat(auditRow["actor_id"]).isEqualTo(userId)
    }

    @Test
    fun `DISABLED rejection does NOT emit login_success audit row`() {
        rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            Map::class.java,
        )
        val userId = jdbc.queryForObject(
            "SELECT id FROM identity.\"user\" WHERE subject = 'alice'",
            UUID::class.java,
        )!!
        jdbc.update("UPDATE identity.\"user\" SET status = 'DISABLED' WHERE id = ?", userId)
        jdbc.update("DELETE FROM audit.audit_event")  // reset audit trail

        rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            Map::class.java,
        )

        // Second attempt (DISABLED) must NOT have emitted login_success.
        val successCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit.audit_event WHERE action = 'identity.user.login.success'",
            Int::class.java,
        ) ?: 0
        assertThat(successCount).isZero()
    }

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
                    "name" to "User $subject",
                ),
            ),
        ).serialize()

    private fun bearer(token: String): HttpHeaders = HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}
