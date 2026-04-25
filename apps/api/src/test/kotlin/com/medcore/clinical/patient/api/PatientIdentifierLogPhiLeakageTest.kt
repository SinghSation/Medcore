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
 * Rule 01 operational control for the identifier write path
 * (Phase 4A.3). Extends the `LogPhiLeakageTest` pattern from
 * 3F.1 with the identifier-specific PHI surface.
 *
 * **Tokens asserted absent from logs:**
 *   - `value` for DRIVERS_LICENSE (strongly PHI — identifier-
 *     style data element per 45 CFR §164.514(b))
 *   - `issuer` (implementation detail — not audited; must not
 *     leak)
 *   - Bearer token
 *
 * **What IS allowed:**
 *   - `type` (closed-enum token, present in audit reason slug)
 *   - Identifier UUID (resource_id)
 *   - Patient UUID
 *   - Tenant / user UUIDs
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class PatientIdentifierLogPhiLeakageTest {

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
        jdbc.update("DELETE FROM clinical.problem")
        jdbc.update("DELETE FROM clinical.allergy")
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `identifier value and issuer never appear in logs on add + revoke`(
        output: CapturedOutput,
    ) {
        // Distinctive tokens highly unlikely to appear in logs for
        // any reason OTHER than a PHI leak.
        val distinctiveIssuer = "Qrstuv-Payer-${UUID.randomUUID()}"
        val distinctiveValue = "LIC-${UUID.randomUUID()}-DistinctiveAlpha"

        val subject = "phi-id-${UUID.randomUUID()}"
        val token = tokenFor(subject)

        val owner = provisionUser(subject, token)
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")

        val createPatient = postPatient(token, "acme-health")
        assertThat(createPatient.statusCode).isEqualTo(HttpStatus.CREATED)
        val patientId = UUID.fromString(
            (createPatient.body!!["data"] as Map<*, *>)["id"] as String,
        )

        val addIdentifier = postIdentifier(
            token, "acme-health", patientId,
            issuer = distinctiveIssuer, value = distinctiveValue,
        )
        assertThat(addIdentifier.statusCode).isEqualTo(HttpStatus.CREATED)
        val identifierId = UUID.fromString(
            (addIdentifier.body!!["data"] as Map<*, *>)["id"] as String,
        )

        // Revoke path — second opportunity for a leak.
        val revoke = delete(token, "acme-health", patientId, identifierId)
        assertThat(revoke.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val captured = output.all
        assertThat(captured)
            .describedAs("identifier value must never appear in logs")
            .doesNotContain(distinctiveValue)
        assertThat(captured)
            .describedAs("identifier issuer must never appear in logs")
            .doesNotContain(distinctiveIssuer)
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
    private fun postPatient(token: String, slug: String): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(token).apply { add("X-Medcore-Tenant", slug) }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients",
            HttpMethod.POST,
            HttpEntity(
                """{"nameGiven":"Ada","nameFamily":"Lovelace","birthDate":"1960-05-15","administrativeSex":"female"}""",
                headers,
            ),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun postIdentifier(
        token: String,
        slug: String,
        patientId: UUID,
        issuer: String,
        value: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(token).apply { add("X-Medcore-Tenant", slug) }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId/identifiers",
            HttpMethod.POST,
            HttpEntity(
                """{"type":"DRIVERS_LICENSE","issuer":"$issuer","value":"$value"}""",
                headers,
            ),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun delete(
        token: String,
        slug: String,
        patientId: UUID,
        identifierId: UUID,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(token).apply { add("X-Medcore-Tenant", slug) }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId/identifiers/$identifierId",
            HttpMethod.DELETE,
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
