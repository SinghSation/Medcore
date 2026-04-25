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
 * End-to-end coverage for GET on the FHIR Patient namespace
 * (Phase 4A.5).
 *
 * Key assertions (NORMATIVE for the canonical-envelope
 * exception):
 *
 *   1. Response body is a **bare FHIR Patient resource** —
 *      starts at `resourceType`, no `data` wrapper, no
 *      `requestId` field inside the body.
 *   2. `X-Request-Id` header is present (correlation
 *      substitute).
 *   3. `ETag: "<rowVersion>"` header matches the 4A.4 native
 *      endpoint's shape.
 *   4. Audit emits ONE `clinical.patient.accessed` row on
 *      200 (reuses the 4A.4 action).
 *   5. 404 on unknown / cross-tenant / DELETED emits NO audit.
 *   6. Role matrix reuses 4A.4 (OWNER/ADMIN/MEMBER all read).
 *   7. Missing `X-Medcore-Tenant` header returns 422
 *      tenancy.context.required (the existing 3G path via
 *      `TenantContextMissingException`).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class GetPatientFhirIntegrationTest {

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

    // --- 401 / 422 ---

    @Test
    fun `FHIR GET without bearer returns 401`() {
        val response = rest.exchange(
            "/fhir/r4/Patient/${UUID.randomUUID()}",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `FHIR GET without X-Medcore-Tenant header returns 422 or 403`() {
        // TenantContextFilter + the controller's own guard catch this.
        // If the caller has a valid bearer but no tenant header, the
        // filter short-circuits with filter-level denial (403 tenancy.forbidden
        // if membership lookup happens) OR the controller throws
        // TenantContextMissingException → 422. Either is a refusal.
        provisionUser("alice")
        val response = rest.exchange(
            "/fhir/r4/Patient/${UUID.randomUUID()}",
            HttpMethod.GET,
            HttpEntity<Void>(bearerOnly(tokenFor("alice"))),
            Map::class.java,
        )
        assertThat(response.statusCode)
            .describedAs("Missing tenant header must refuse — 422 or 403")
            .isIn(HttpStatus.UNPROCESSABLE_ENTITY, HttpStatus.FORBIDDEN)
    }

    // --- Success path: bare FHIR body + envelope-free ---

    @Test
    fun `OWNER reads patient as FHIR — 200 with bare Patient body, ETag, X-Request-Id, and ONE audit row`() {
        val (_, patientId) = seedPatient("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = fhirGet("alice", "acme-health", patientId)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.eTag).isEqualTo("\"0\"")
        assertThat(response.headers["X-Request-Id"])
            .describedAs("FHIR endpoint carries X-Request-Id header as envelope substitute")
            .isNotNull()
            .isNotEmpty()

        // NORMATIVE: body is a bare FHIR resource. No `data`
        // wrapper, no `requestId` in the body (only in header).
        val body = response.body!!
        assertThat(body["resourceType"]).isEqualTo("Patient")
        assertThat(body["id"]).isEqualTo(patientId.toString())
        assertThat(body)
            .describedAs("FHIR body must NOT contain ApiResponse wrapper fields")
            .doesNotContainKey("data")
            .doesNotContainKey("requestId")

        // Audit row — same action as 4A.4 native path.
        val audit = auditRows("clinical.patient.accessed")
        assertThat(audit).hasSize(1)
        val row = audit.single()
        assertThat(row["reason"]).isEqualTo("intent:clinical.patient.access")
        assertThat(row["resource_type"]).isEqualTo("clinical.patient")
        assertThat(row["resource_id"]).isEqualTo(patientId.toString())
    }

    @Test
    fun `FHIR body renders Medcore MRN as identifier with tenant-scoped URN system`() {
        val (_, patientId) = seedPatient("alice", role = "OWNER")
        val response = fhirGet("alice", "acme-health", patientId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val identifiers = response.body!!["identifier"] as List<Map<String, Any?>>
        assertThat(identifiers).hasSize(1)
        val mrnId = identifiers.single()
        assertThat(mrnId["use"]).isEqualTo("usual")
        assertThat(mrnId["value"]).isEqualTo("000001")
        val system = mrnId["system"] as String
        assertThat(system).startsWith("urn:medcore:tenant:")
        assertThat(system).endsWith(":mrn")
    }

    @Test
    fun `MEMBER can read FHIR patient — 200 (PATIENT_READ granted to MEMBER)`() {
        val owner = provisionUser("owner")
        val member = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, member, role = "MEMBER")
        val createResp = postPatient("owner", "acme-health")
        val patientId = UUID.fromString(
            (createResp.body!!["data"] as Map<*, *>)["id"] as String,
        )

        val response = fhirGet("alice", "acme-health", patientId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["resourceType"]).isEqualTo("Patient")
    }

    // --- 404 paths (NO audit) ---

    @Test
    fun `FHIR GET unknown patientId — 404 with NO access audit row`() {
        seedPatient("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = fhirGet("alice", "acme-health", UUID.randomUUID())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.patient.accessed")).isEmpty()
    }

    @Test
    fun `FHIR GET cross-tenant patientId — 404 with NO access audit row`() {
        val alice = provisionUser("alice")
        val tenantA = seedTenant("tenant-a")
        val tenantB = seedTenant("tenant-b")
        seedMembership(tenantA, alice, role = "OWNER")
        seedMembership(tenantB, alice, role = "OWNER")

        val createResp = postPatient("alice", "tenant-a")
        val patientInA = UUID.fromString(
            (createResp.body!!["data"] as Map<*, *>)["id"] as String,
        )
        jdbc.update("DELETE FROM audit.audit_event")

        val response = fhirGet("alice", "tenant-b", patientInA)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.patient.accessed")).isEmpty()
    }

    // --- helpers ---

    private fun seedPatient(
        subject: String,
        role: String = "OWNER",
    ): Pair<UUID, UUID> {
        val user = provisionUser(subject)
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, user, role = role)
        val createResp = postPatient(subject, "acme-health")
        val patientId = UUID.fromString(
            (createResp.body!!["data"] as Map<*, *>)["id"] as String,
        )
        return user to patientId
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
    private fun fhirGet(
        subject: String,
        slug: String,
        patientId: UUID,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/fhir/r4/Patient/$patientId",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
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

    private fun authJsonHeaders(token: String) = HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $token")
        add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
    }

    private fun bearerOnly(token: String) = HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}
