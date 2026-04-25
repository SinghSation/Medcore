package com.medcore.platform.observability

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
 * Load-bearing proof that a single `request_id` reaches all four
 * surfaces required by Phase 3F.1 DoD (docs/product/03-definition-of-
 * done.md §3.1.1):
 *
 *   1. Generated (no inbound header) or accepted (inbound UUID).
 *   2. Response header (`X-Request-Id`).
 *   3. `audit.audit_event.request_id` column (end-to-end through
 *       RequestIdFilter -> MDC -> RequestMetadataProvider -> JdbcAuditWriter).
 *   4. The log-line surface is proven by [StructuredLoggingTest]; this
 *      test focuses on HTTP/audit correlation.
 *
 * The test intentionally exercises two modes:
 *   - **Generated**: client sends no header; filter mints UUIDv4;
 *     response, MDC, audit all carry that generated id.
 *   - **Accepted**: client sends a well-formed UUID; filter
 *     propagates verbatim; response, MDC, audit all carry the
 *     client's id.
 *
 * Audit source of truth:
 *   The first authenticated `GET /api/v1/me` call on a fresh
 *   subject yields two audit rows synchronously in the request's
 *   transaction (`identity.user.provisioned` + `identity.user.login.success`,
 *   ADR-003 §7). Both MUST carry the same `request_id` as the
 *   response. A third `tenancy.context.set` row would only appear
 *   if the caller supplied `X-Medcore-Tenant`, which we do not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class RequestIdAuditCorrelationTest {

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
        jdbc.update("DELETE FROM clinical.patient_mrn_counter")
        jdbc.update("DELETE FROM clinical.patient_identifier")
        jdbc.update("DELETE FROM clinical.problem")
        jdbc.update("DELETE FROM clinical.allergy")
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `generated request id reaches response header and audit rows`() {
        val subject = "corr-${UUID.randomUUID()}"
        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor(subject))),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val responseId = response.headers.getFirst(REQUEST_ID_HEADER)
        assertThat(responseId).describedAs("response MUST carry X-Request-Id").isNotNull()
        // Generated ids are UUIDs — parseable by java.util.UUID.
        UUID.fromString(responseId)

        val auditRequestIds = jdbc.queryForList(
            """
            SELECT action, request_id
              FROM audit.audit_event
             ORDER BY recorded_at
            """.trimIndent(),
        )

        assertThat(auditRequestIds).describedAs("two audit rows expected (provisioned + login.success)").hasSize(2)
        auditRequestIds.forEach { row ->
            assertThat(row["request_id"])
                .describedAs("audit row ${row["action"]} must carry same request_id as response header")
                .isEqualTo(responseId)
        }
    }

    @Test
    fun `inbound well-formed request id is propagated to response and audit`() {
        val inbound = UUID.randomUUID().toString()
        val subject = "corr-${UUID.randomUUID()}"

        val headers = bearer(tokenFor(subject))
        headers.set(REQUEST_ID_HEADER, inbound)

        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.getFirst(REQUEST_ID_HEADER)).isEqualTo(inbound)

        val auditRows = jdbc.queryForList(
            """
            SELECT action, request_id
              FROM audit.audit_event
             ORDER BY recorded_at
            """.trimIndent(),
        )
        assertThat(auditRows).hasSize(2)
        auditRows.forEach { row ->
            assertThat(row["request_id"])
                .describedAs("audit row ${row["action"]} must carry the inbound request_id")
                .isEqualTo(inbound)
        }
    }

    @Test
    fun `inbound malformed request id is replaced by fresh uuid`() {
        val subject = "corr-${UUID.randomUUID()}"
        val headers = bearer(tokenFor(subject))
        headers.set(REQUEST_ID_HEADER, "not-a-uuid")

        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val responseId = response.headers.getFirst(REQUEST_ID_HEADER)!!
        assertThat(responseId).isNotEqualTo("not-a-uuid")
        // Parses as UUID (fresh generated).
        UUID.fromString(responseId)

        val auditRows = jdbc.queryForList(
            """
            SELECT request_id
              FROM audit.audit_event
            """.trimIndent(),
        )
        auditRows.forEach { row ->
            assertThat(row["request_id"]).isEqualTo(responseId)
        }
    }

    private fun tokenFor(subject: String): String =
        mockOAuth2Server.issueToken(
            issuerId = MOCK_ISSUER_ID,
            clientId = "medcore-test-client",
            tokenCallback = DefaultOAuth2TokenCallback(
                issuerId = MOCK_ISSUER_ID,
                subject = subject,
                claims = mapOf(
                    "email_verified" to true,
                    "preferred_username" to "$subject-user",
                ),
            ),
        ).serialize()

    private fun bearer(token: String) = HttpHeaders().apply {
        set(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    companion object {
        private const val REQUEST_ID_HEADER: String = "X-Request-Id"
    }
}
