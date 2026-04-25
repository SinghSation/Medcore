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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
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
 * End-to-end integration coverage for
 * `POST /api/v1/tenants/{slug}/patients/{patientId}/identifiers`
 * (Phase 4A.3).
 *
 * First real-pattern-reuse exercise: every assertion maps to a
 * `clinical-write-pattern.md` §10 checklist item. The pattern
 * is validated by the fact that this suite follows 4A.2's
 * `CreatePatientIntegrationTest` shape mechanically, with only
 * domain-specific substitutions (identifier fields, nested URL).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class AddPatientIdentifierIntegrationTest {

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

    // --- 401 ---

    @Test
    fun `POST without bearer token returns 401`() {
        val response = rest.exchange(
            "/api/v1/tenants/acme-health/patients/${UUID.randomUUID()}/identifiers",
            HttpMethod.POST,
            HttpEntity(MINIMAL_BODY, jsonHeaders()),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // --- Success ---

    @Test
    fun `OWNER adds DRIVERS_LICENSE identifier — 201 with ETag and audit row`() {
        val (_, _, patientId) = seedOwnerAndPatient()

        val response = post("alice", "acme-health", patientId, MINIMAL_BODY)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.headers.eTag).isEqualTo("\"0\"")

        val data = response.body!!["data"] as Map<*, *>
        assertThat(data["type"]).isEqualTo("DRIVERS_LICENSE")
        assertThat(data["issuer"]).isEqualTo("CA")
        assertThat(data["value"]).isEqualTo("D1234567")
        assertThat(data["rowVersion"]).isEqualTo(0)

        // DB row present
        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM clinical.patient_identifier WHERE patient_id = ?",
            Int::class.java, patientId,
        )
        assertThat(count).isEqualTo(1)

        // Audit row shape (NORMATIVE contract)
        val audit = auditRows("clinical.patient.identifier.added")
        assertThat(audit).hasSize(1)
        val row = audit.single()
        assertThat(row["reason"])
            .isEqualTo("intent:clinical.patient.identifier.add|type:DRIVERS_LICENSE")
        assertThat(row["resource_type"]).isEqualTo("clinical.patient.identifier")
        assertThat(row["outcome"]).isEqualTo("SUCCESS")
    }

    @Test
    fun `ADMIN can add identifier — 201`() {
        val owner = provisionUser("owner")
        val admin = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, admin, role = "ADMIN")

        val createResp = postPatient("owner", "acme-health")
        val patientId = UUID.fromString(
            (createResp.body!!["data"] as Map<*, *>)["id"] as String,
        )

        val response = post("alice", "acme-health", patientId, MINIMAL_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    // --- Authorization ---

    @Test
    fun `MEMBER cannot add identifier — 403 + denial audit row`() {
        val owner = provisionUser("owner")
        val member = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, member, role = "MEMBER")

        val createResp = postPatient("owner", "acme-health")
        val patientId = UUID.fromString(
            (createResp.body!!["data"] as Map<*, *>)["id"] as String,
        )

        val response = post("alice", "acme-health", patientId, MINIMAL_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val denial = auditRows("authz.write.denied").filter {
            (it["reason"] as String).startsWith("intent:clinical.patient.identifier.add")
        }
        assertThat(denial).hasSize(1)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:clinical.patient.identifier.add|denial:insufficient_authority")
    }

    // --- Cross-tenant ---

    @Test
    fun `cross-tenant patientId — 404 (identical to unknown id)`() {
        val (_, _, _) = seedOwnerAndPatient()
        val otherPatientId = UUID.randomUUID()
        val response = post("alice", "acme-health", otherPatientId, MINIMAL_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    // --- UNIQUE constraint ---

    @Test
    fun `duplicate (patient_id, type, issuer, value) — 409 resource_conflict`() {
        val (_, _, patientId) = seedOwnerAndPatient()

        val first = post("alice", "acme-health", patientId, MINIMAL_BODY)
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)

        val duplicate = post("alice", "acme-health", patientId, MINIMAL_BODY)
        assertThat(duplicate.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(duplicate.body!!["code"]).isEqualTo("resource.conflict")
    }

    // --- Validation ---

    @Test
    fun `missing issuer — 422`() {
        val (_, _, patientId) = seedOwnerAndPatient()
        val response = post(
            "alice", "acme-health", patientId,
            """{"type":"DRIVERS_LICENSE","value":"D1234567"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `unknown type wire value — 422`() {
        val (_, _, patientId) = seedOwnerAndPatient()
        val response = post(
            "alice", "acme-health", patientId,
            """{"type":"UNKNOWN_TYPE","issuer":"CA","value":"D1234567"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    // --- helpers ---

    private data class Seed(val userId: UUID, val tenantId: UUID, val patientId: UUID)

    private fun seedOwnerAndPatient(): Seed {
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        val createResp = postPatient("alice", "acme-health")
        check(createResp.statusCode == HttpStatus.CREATED) {
            "seed patient create failed: ${createResp.statusCode}"
        }
        val patientId = UUID.fromString(
            (createResp.body!!["data"] as Map<*, *>)["id"] as String,
        )
        return Seed(owner, tenant, patientId)
    }

    private fun provisionUser(subject: String): UUID {
        rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearerOnly(tokenFor(subject))),
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
    private fun postPatient(subject: String, slug: String): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
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
    private fun post(
        subject: String,
        slug: String,
        patientId: UUID,
        body: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId/identifiers",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    private fun auditRows(action: String): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT action, reason, outcome, resource_type, resource_id
              FROM audit.audit_event
             WHERE action = ?
             ORDER BY recorded_at
            """.trimIndent(),
            action,
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
                    "name" to "User $subject",
                ),
            ),
        ).serialize()

    private fun jsonHeaders() = HttpHeaders().apply {
        add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
    }

    private fun authJsonHeaders(token: String) = HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $token")
        add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
    }

    private fun bearerOnly(token: String) = HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    private companion object {
        const val MINIMAL_BODY: String =
            """{"type":"DRIVERS_LICENSE","issuer":"CA","value":"D1234567"}"""
    }
}
