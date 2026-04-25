package com.medcore.clinical.encounter.api

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
 * Rule 01 operational control for the encounter-note path
 * (Phase 4D.1, VS1 Chunk E). Note body is PHI — must never
 * appear in stdout or in any audit row.
 *
 * The audit-row discipline is already asserted in
 * `CreateEncounterNoteIntegrationTest`; this test is the
 * complementary stdout-side control.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class EncounterNoteLogPhiLeakageTest {

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
        jdbc.update("DELETE FROM clinical.encounter_note")
        jdbc.update("DELETE FROM clinical.encounter")
        jdbc.update("DELETE FROM clinical.patient_mrn_counter")
        jdbc.update("DELETE FROM clinical.patient_identifier")
        jdbc.update("DELETE FROM clinical.allergy")
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `note body never appears in logs on POST or GET`(
        output: CapturedOutput,
    ) {
        val subject = "phi-note-${UUID.randomUUID()}"
        val token = tokenFor(subject)

        val owner = provisionUser(subject, token)
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")

        val distinctive = "Zyxwvut-NoteBody-${UUID.randomUUID()}"

        // Seed via the full native HTTP path.
        val patientId = createPatient(token, "acme-health")
        val encounterId = createEncounter(token, "acme-health", patientId)

        val postResp = createNote(token, "acme-health", encounterId, distinctive)
        assertThat(postResp.statusCode).isEqualTo(HttpStatus.CREATED)

        val listResp = listNotes(token, "acme-health", encounterId)
        assertThat(listResp.statusCode).isEqualTo(HttpStatus.OK)

        val captured = output.all
        assertThat(captured)
            .describedAs("note body MUST NOT appear in logs on POST or GET")
            .doesNotContain(distinctive)
        assertThat(captured)
            .describedAs("bearer token MUST NOT appear in logs")
            .doesNotContain(token)

        // Also assert the audit rows never carry the body.
        val rows = jdbc.queryForList(
            """
            SELECT reason FROM audit.audit_event
             WHERE action IN (
               'clinical.encounter.note.created',
               'clinical.encounter.note.list_accessed'
             )
            """.trimIndent(),
        )
        rows.forEach { row ->
            assertThat(row["reason"] as String)
                .describedAs("audit row's reason MUST NOT contain note body")
                .doesNotContain(distinctive)
        }
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
    private fun createPatient(token: String, slug: String): UUID {
        val headers = authJsonHeaders(token).apply { add("X-Medcore-Tenant", slug) }
        val resp = rest.exchange(
            "/api/v1/tenants/$slug/patients",
            HttpMethod.POST,
            HttpEntity(
                """{"nameGiven":"Ada","nameFamily":"Lovelace","birthDate":"1960-05-15","administrativeSex":"female"}""",
                headers,
            ),
            Map::class.java,
        )
        val data = resp.body!!["data"] as Map<String, Any>
        return UUID.fromString(data["id"] as String)
    }

    @Suppress("UNCHECKED_CAST")
    private fun createEncounter(token: String, slug: String, patientId: UUID): UUID {
        val headers = authJsonHeaders(token).apply { add("X-Medcore-Tenant", slug) }
        val resp = rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId/encounters",
            HttpMethod.POST,
            HttpEntity("""{"encounterClass":"AMB"}""", headers),
            Map::class.java,
        )
        val data = resp.body!!["data"] as Map<String, Any>
        return UUID.fromString(data["id"] as String)
    }

    @Suppress("UNCHECKED_CAST")
    private fun createNote(
        token: String,
        slug: String,
        encounterId: UUID,
        body: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(token).apply { add("X-Medcore-Tenant", slug) }
        return rest.exchange(
            "/api/v1/tenants/$slug/encounters/$encounterId/notes",
            HttpMethod.POST,
            HttpEntity("""{"body":"$body"}""", headers),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun listNotes(
        token: String,
        slug: String,
        encounterId: UUID,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(token).apply { add("X-Medcore-Tenant", slug) }
        return rest.exchange(
            "/api/v1/tenants/$slug/encounters/$encounterId/notes",
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
