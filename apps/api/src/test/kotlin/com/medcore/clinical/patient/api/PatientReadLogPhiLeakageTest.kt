package com.medcore.clinical.patient.api

import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Rule 01 operational control for the patient READ path
 * (Phase 4A.4). Reads disclose PHI — logs must not.
 *
 * Extends the 4A.2 write-path log-leakage pattern into
 * reads:
 *
 *   1. Seed a patient with distinctive PHI tokens.
 *   2. GET the patient.
 *   3. Grep captured stdout for the tokens — any hit = a
 *      log leak.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class PatientReadLogPhiLeakageTest {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mockOAuth2Server: MockOAuth2Server

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
        jdbc.update("DELETE FROM clinical.allergy")
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `patient name and demographics never appear in logs on GET`(
        output: CapturedOutput,
    ) {
        val subject = "phi-read-${UUID.randomUUID()}"
        val token = tokenFor(subject)

        val distinctiveGiven = "Zyxwvut-Given-${UUID.randomUUID()}"
        val distinctiveFamily = "Qponmlk-Family-${UUID.randomUUID()}"
        val distinctiveLanguage = "nan-Latn-TW"

        val owner = provisionUser(subject, token)
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")

        // Seed with distinctive PHI.
        val createBody = """
            {"nameGiven":"$distinctiveGiven",
             "nameFamily":"$distinctiveFamily",
             "birthDate":"1974-03-22",
             "administrativeSex":"female",
             "preferredLanguage":"$distinctiveLanguage"}
        """.trimIndent()
        val createResp = postPatient(token, "acme-health", createBody)
        assertThat(createResp.statusCode).isEqualTo(HttpStatus.CREATED)
        val patientId = UUID.fromString(
            (createResp.body!!["data"] as Map<*, *>)["id"] as String,
        )

        // The READ path — second opportunity for PHI leakage
        // beyond the 4A.2 write path.
        val response = get(token, "acme-health", patientId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val captured = output.all
        assertThat(captured)
            .describedAs("patient nameGiven must never appear in logs on GET")
            .doesNotContain(distinctiveGiven)
        assertThat(captured)
            .describedAs("patient nameFamily must never appear in logs on GET")
            .doesNotContain(distinctiveFamily)
        assertThat(captured)
            .describedAs("patient preferredLanguage must never appear in logs on GET")
            .doesNotContain(distinctiveLanguage)
        assertThat(captured)
            .describedAs("bearer token must never appear in logs")
            .doesNotContain(token)
    }

    // ---- helpers ----

    private fun provisionUser(subject: String, token: String): UUID {
        rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearerOnly(token)),
            Map::class.java,
        )
        return jdbc.queryForObject(
            "SELECT id FROM identity.\"user\" WHERE subject = ?",
            UUID::class.java,
            subject,
        )!!
    }

    private fun seedTenant(slug: String): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        jdbc.update(
            """
            INSERT INTO tenancy.tenant(
                id, slug, display_name, status, created_at, updated_at, row_version
            ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
            """.trimIndent(),
            id, slug, "Display $slug",
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
        )
        return id
    }

    private fun seedMembership(tenant: UUID, user: UUID, role: String) {
        val now = Instant.now()
        jdbc.update(
            """
            INSERT INTO tenancy.tenant_membership(
                id, tenant_id, user_id, role, status,
                created_at, updated_at, row_version
            ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(), tenant, user, role,
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun postPatient(
        token: String,
        slug: String,
        body: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(token).apply { add("X-Medcore-Tenant", slug) }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun get(
        token: String,
        slug: String,
        patientId: UUID,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(token).apply { add("X-Medcore-Tenant", slug) }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
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

    private fun authJsonHeaders(token: String) = HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $token")
        add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
    }

    private fun bearerOnly(token: String) = HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}
