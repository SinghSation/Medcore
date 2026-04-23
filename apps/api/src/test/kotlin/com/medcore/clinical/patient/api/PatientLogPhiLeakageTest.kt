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
 * Rule 01 operational control for PHI-bearing write paths
 * (Phase 4A.2).
 *
 * Fires a `POST /patients` + `PATCH /patients/{id}` with known
 * synthetic PHI tokens (name, DOB, language). Grep-fails the
 * captured stdout for each token — if ANY of them land in log
 * output, the test fails.
 *
 * Extends the [com.medcore.platform.observability.LogPhiLeakageTest]
 * pattern from 3F.1 with clinical-field coverage: patient name
 * parts, birth date, demographic codes. This is the first test
 * in the clinical slice that guards against the PHI-in-logs
 * failure mode.
 *
 * **Tokens asserted absent from logs:**
 *   - Bearer token itself (inherited from 3F.1 discipline).
 *   - Patient `nameGiven` / `nameFamily` / `nameMiddle`.
 *   - Patient `preferredName`.
 *   - Patient `birthDate` (as ISO string).
 *   - Patient `preferredLanguage` (BCP 47 tag).
 *   - Synthetic gender-identity code (if sent).
 *
 * **What IS allowed in logs** (and NOT asserted absent):
 *   - Tenant id (UUID).
 *   - Patient id (UUID) — tenant-scoped opaque identifier.
 *   - MRN (tenant-scoped identifier; NOT broadly PHI-equivalent
 *     to name+DOB).
 *   - User id (UUID).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class PatientLogPhiLeakageTest {

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
        jdbc.update("DELETE FROM clinical.patient_identifier")
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM clinical.patient_mrn_counter")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `application logs never contain patient PHI on successful create + update`(output: CapturedOutput) {
        // Distinctive synthetic PHI tokens — highly unlikely to
        // appear in logs for any reason OTHER than a PHI leak.
        val given = "Zyxwvut-Given-${UUID.randomUUID()}"
        val family = "Qponmlk-Family-${UUID.randomUUID()}"
        val middle = "Unusualmiddle-${UUID.randomUUID()}"
        val preferred = "Nickname-${UUID.randomUUID()}"
        val birthDate = "1974-03-22"
        val language = "nan-Latn-TW"

        val subject = "phi-${UUID.randomUUID()}"
        val token = tokenFor(subject)

        val owner = provisionUser(subject, token)
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")

        val createBody = """
            {"nameGiven":"$given","nameFamily":"$family","nameMiddle":"$middle",
             "preferredName":"$preferred","birthDate":"$birthDate",
             "administrativeSex":"female","preferredLanguage":"$language"}
        """.trimIndent()

        val created = post(token, "acme-health", createBody)
        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        val patientId = UUID.fromString(
            (created.body!!["data"] as Map<*, *>)["id"] as String,
        )

        // PATCH the demographics — a second opportunity for a log leak.
        val patched = patch(
            token, "acme-health", patientId,
            ifMatch = "0",
            body = """{"nameFamily":"NewFamily-${UUID.randomUUID()}","preferredLanguage":"en"}""",
        )
        assertThat(patched.statusCode).isEqualTo(HttpStatus.OK)

        // Assert none of the PHI tokens appear in any log output.
        val captured = output.all
        assertThat(captured)
            .describedAs("nameGiven must never appear in logs")
            .doesNotContain(given)
        assertThat(captured)
            .describedAs("nameFamily must never appear in logs")
            .doesNotContain(family)
        assertThat(captured)
            .describedAs("nameMiddle must never appear in logs")
            .doesNotContain(middle)
        assertThat(captured)
            .describedAs("preferredName must never appear in logs")
            .doesNotContain(preferred)
        assertThat(captured)
            .describedAs("birthDate must never appear in logs")
            .doesNotContain(birthDate)
        assertThat(captured)
            .describedAs("preferredLanguage must never appear in logs")
            .doesNotContain(language)
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
    private fun post(
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
    private fun patch(
        token: String,
        slug: String,
        patientId: UUID,
        ifMatch: String,
        body: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(token).apply {
            add("X-Medcore-Tenant", slug)
            add("If-Match", ifMatch)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId",
            HttpMethod.PATCH,
            HttpEntity(body, headers),
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
