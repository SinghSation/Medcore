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
 * End-to-end coverage for
 * `GET /api/v1/tenants/{slug}/patients/{patientId}` (Phase 4A.4).
 *
 * First PHI READ endpoint in Medcore. Proves:
 *
 *   1. Happy path: 200 + body + ETag + audit row.
 *   2. Filter-level denials (SUSPENDED, not-a-member) → 403
 *      from `TenantContextFilter`; no `CLINICAL_PATIENT_ACCESSED`
 *      row; filter's own `tenancy.membership.denied` row instead.
 *   3. 404 paths (unknown id, cross-tenant id, DELETED patient) →
 *      404 + NO audit row (no disclosure).
 *   4. MEMBER can read (4A.1 role map grants PATIENT_READ to
 *      MEMBER).
 *   5. Audit emission shape matches NORMATIVE contract.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class GetPatientIntegrationTest {

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
    fun `GET without bearer token returns 401`() {
        val response = rest.exchange(
            "/api/v1/tenants/acme-health/patients/${UUID.randomUUID()}",
            HttpMethod.GET,
            HttpEntity<Void>(jsonHeaders()),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // --- Success paths ---

    @Test
    fun `OWNER reads patient — 200 with body, ETag, + CLINICAL_PATIENT_ACCESSED audit row`() {
        val (_, patientId) = seedPatient("alice", role = "OWNER")

        // Reset audit to drop seed-create row so the read row is the only matching one.
        jdbc.update("DELETE FROM audit.audit_event")

        val response = get("alice", "acme-health", patientId)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.eTag).isEqualTo("\"0\"")

        val data = response.body!!["data"] as Map<*, *>
        assertThat(data["id"]).isEqualTo(patientId.toString())
        assertThat(data["mrn"]).isEqualTo("000001")
        assertThat(data["nameGiven"]).isEqualTo("Ada")
        assertThat(data["nameFamily"]).isEqualTo("Lovelace")
        assertThat(data["rowVersion"]).isEqualTo(0)

        // NORMATIVE audit-row shape
        val audit = auditRows("clinical.patient.accessed")
        assertThat(audit).hasSize(1)
        val row = audit.single()
        assertThat(row["reason"]).isEqualTo("intent:clinical.patient.access")
        assertThat(row["resource_type"]).isEqualTo("clinical.patient")
        assertThat(row["resource_id"]).isEqualTo(patientId.toString())
        assertThat(row["outcome"]).isEqualTo("SUCCESS")
    }

    @Test
    fun `MEMBER can read patient — 200 (PATIENT_READ granted to all roles)`() {
        val owner = provisionUser("owner")
        val member = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, member, role = "MEMBER")

        val createResp = postPatient("owner", "acme-health")
        val patientId = UUID.fromString(
            (createResp.body!!["data"] as Map<*, *>)["id"] as String,
        )

        val response = get("alice", "acme-health", patientId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val audit = auditRows("clinical.patient.accessed")
        assertThat(audit).hasSize(1)
    }

    @Test
    fun `ADMIN can read patient — 200`() {
        val owner = provisionUser("owner")
        val admin = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, admin, role = "ADMIN")

        val createResp = postPatient("owner", "acme-health")
        val patientId = UUID.fromString(
            (createResp.body!!["data"] as Map<*, *>)["id"] as String,
        )

        val response = get("alice", "acme-health", patientId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    // --- 404 paths (NO audit row) ---

    @Test
    fun `unknown patientId — 404 with NO access audit row`() {
        val (_, _) = seedPatient("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = get("alice", "acme-health", UUID.randomUUID())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        val audit = auditRows("clinical.patient.accessed")
        assertThat(audit)
            .describedAs("404 = no disclosure = no audit emission")
            .isEmpty()
    }

    @Test
    fun `cross-tenant patientId — 404 with NO access audit row (no existence leak)`() {
        val alice = provisionUser("alice")
        val tenantA = seedTenant("tenant-a")
        val tenantB = seedTenant("tenant-b")
        seedMembership(tenantA, alice, role = "OWNER")
        seedMembership(tenantB, alice, role = "OWNER")

        // Alice creates a patient in tenant A.
        val createResp = postPatient("alice", "tenant-a")
        val aliceAsPatientIdInA = UUID.fromString(
            (createResp.body!!["data"] as Map<*, *>)["id"] as String,
        )
        jdbc.update("DELETE FROM audit.audit_event")

        // Alice GETs tenant A's patient via tenant B's URL.
        val response = get("alice", "tenant-b", aliceAsPatientIdInA)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        val audit = auditRows("clinical.patient.accessed")
        assertThat(audit)
            .describedAs("cross-tenant 404 must be identical to unknown 404 — no audit")
            .isEmpty()
    }

    @Test
    fun `DELETED patient — 404 (V14 SELECT policy hides it)`() {
        val (owner, patientId) = seedPatient("alice", role = "OWNER")

        // Flip patient to DELETED directly in DB (4A.4 doesn't ship
        // a soft-delete endpoint; we simulate one).
        jdbc.update(
            "UPDATE clinical.patient SET status = 'DELETED' WHERE id = ?",
            patientId,
        )
        jdbc.update("DELETE FROM audit.audit_event")

        val response = get("alice", "acme-health", patientId)
        assertThat(response.statusCode)
            .describedAs("DELETED patient is invisible to RLS → 404")
            .isEqualTo(HttpStatus.NOT_FOUND)

        assertThat(auditRows("clinical.patient.accessed")).isEmpty()
    }

    // --- Filter-level denials (no CLINICAL_PATIENT_ACCESSED; existing tenancy audit) ---

    @Test
    fun `caller with no membership — 403 from TenantContextFilter, no access audit`() {
        val stranger = provisionUser("alice")
        val ownerOfSomeTenant = provisionUser("owner")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, ownerOfSomeTenant, role = "OWNER")

        val createResp = postPatient("owner", "acme-health")
        val patientId = UUID.fromString(
            (createResp.body!!["data"] as Map<*, *>)["id"] as String,
        )
        jdbc.update("DELETE FROM audit.audit_event")

        val response = get("alice", "acme-health", patientId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val clinicalAudit = auditRows("clinical.patient.accessed")
        assertThat(clinicalAudit).isEmpty()
        val filterAudit = auditRows("tenancy.membership.denied")
        assertThat(filterAudit)
            .describedAs("filter-level denial emits tenancy.membership.denied, not our action")
            .hasSize(1)
    }

    @Test
    fun `SUSPENDED membership — 403 from TenantContextFilter`() {
        val owner = provisionUser("owner")
        val alice = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, alice, role = "OWNER", status = "SUSPENDED")

        val createResp = postPatient("owner", "acme-health")
        val patientId = UUID.fromString(
            (createResp.body!!["data"] as Map<*, *>)["id"] as String,
        )
        jdbc.update("DELETE FROM audit.audit_event")

        val response = get("alice", "acme-health", patientId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

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

    private fun seedMembership(
        tenant: UUID,
        user: UUID,
        role: String,
        status: String = "ACTIVE",
    ) {
        val now = Instant.now()
        jdbc.update(
            """
            INSERT INTO tenancy.tenant_membership(
                id, tenant_id, user_id, role, status,
                created_at, updated_at, row_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(), tenant, user, role, status,
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
    private fun get(
        subject: String,
        slug: String,
        patientId: UUID,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId",
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
}
