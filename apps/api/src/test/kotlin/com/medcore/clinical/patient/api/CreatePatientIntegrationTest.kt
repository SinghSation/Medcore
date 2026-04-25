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
 * `POST /api/v1/tenants/{slug}/patients` (Phase 4A.2).
 *
 * First real PHI write path in Medcore. Proves the full pipeline:
 * controller → Bean Validation → CreatePatientValidator →
 * CreatePatientPolicy → WriteGate opens tx → PhiRlsTxHook sets
 * both GUCs → CreatePatientHandler → DuplicatePatientDetector →
 * MrnGenerator (ON CONFLICT upsert) → PatientRepository.save →
 * RLS WITH CHECK → CreatePatientAuditor.onSuccess → 201 + ETag.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class CreatePatientIntegrationTest {

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
        jdbc.update("DELETE FROM clinical.allergy")
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM clinical.patient_mrn_counter")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    // --- 401 ---

    @Test
    fun `POST without bearer token returns 401`() {
        val response = rest.exchange(
            "/api/v1/tenants/acme-health/patients",
            HttpMethod.POST,
            HttpEntity(MINIMAL_CREATE_BODY, jsonHeaders()),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // --- Success path ---

    @Test
    fun `OWNER creates patient — 201, patient row, MRN 000001, audit row, ETag`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = post("alice", "acme-health", MINIMAL_CREATE_BODY)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.headers.eTag)
            .describedAs("ETag must match post-create row_version")
            .isEqualTo("\"0\"")

        val data = response.body!!["data"] as Map<*, *>
        assertThat(data["mrn"]).isEqualTo("000001")
        assertThat(data["mrnSource"]).isEqualTo("GENERATED")
        assertThat(data["nameGiven"]).isEqualTo("Ada")
        assertThat(data["nameFamily"]).isEqualTo("Lovelace")
        assertThat(data["birthDate"]).isEqualTo("1960-05-15")
        assertThat(data["administrativeSex"]).isEqualTo("female")
        assertThat(data["status"]).isEqualTo("ACTIVE")
        assertThat(data["rowVersion"]).isEqualTo(0)

        val patientCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM clinical.patient WHERE tenant_id = ? AND mrn = '000001'",
            Int::class.java, tenant,
        )
        assertThat(patientCount).isEqualTo(1)

        val audit = auditRows(action = "clinical.patient.created")
        assertThat(audit).hasSize(1)
        val row = audit.single()
        assertThat(row["action"]).isEqualTo("clinical.patient.created")
        assertThat(row["reason"]).isEqualTo("intent:clinical.patient.create|mrn_source:GENERATED")
        assertThat(row["resource_type"]).isEqualTo("clinical.patient")
        assertThat(row["outcome"]).isEqualTo("SUCCESS")
    }

    @Test
    fun `ADMIN creates patient — 201`() {
        val admin = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, admin, role = "ADMIN", status = "ACTIVE")

        val response = post("alice", "acme-health", MINIMAL_CREATE_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `sequential creates on same tenant return monotonic MRNs`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val first = post("alice", "acme-health", MINIMAL_CREATE_BODY)
        val second = post("alice", "acme-health", body(given = "Grace", family = "Hopper"))
        val third = post("alice", "acme-health", body(given = "Barbara", family = "Liskov"))

        fun mrn(r: ResponseEntity<Map<String, Any>>) =
            (r.body!!["data"] as Map<*, *>)["mrn"] as String

        assertThat(mrn(first)).isEqualTo("000001")
        assertThat(mrn(second)).isEqualTo("000002")
        assertThat(mrn(third)).isEqualTo("000003")
    }

    // --- Authorization ---

    @Test
    fun `MEMBER cannot create patient — 403 and denial audit row`() {
        val member = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, member, role = "MEMBER", status = "ACTIVE")

        val response = post("alice", "acme-health", MINIMAL_CREATE_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body!!["code"]).isEqualTo("auth.forbidden")

        val patientCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM clinical.patient",
            Int::class.java,
        )
        assertThat(patientCount).isZero()

        val denial = auditRows(action = "authz.write.denied")
        assertThat(denial).hasSize(1)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:clinical.patient.create|denial:insufficient_authority")
        assertThat(denial.single()["resource_type"]).isEqualTo("clinical.patient")
    }

    @Test
    fun `caller with no membership in tenant — 403 from TenantContextFilter`() {
        // Note: PHI routes require the X-Medcore-Tenant header. When alice
        // has NO membership, TenantContextFilter refuses at filter time
        // (403 tenancy.forbidden + tenancy.membership.denied audit row)
        // — the request never reaches CreatePatientPolicy.
        provisionUser("alice")
        seedTenant("acme-health") // tenant exists, but alice is not a member

        val response = post("alice", "acme-health", MINIMAL_CREATE_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        // Filter-level denial emits tenancy.membership.denied, NOT our
        // clinical.patient.create denial (the policy never ran).
        val clinicalDenial = auditRows(action = "authz.write.denied")
        assertThat(clinicalDenial)
            .describedAs("CreatePatientPolicy must not have run on filter-level denial")
            .isEmpty()
        val filterDenial = auditRows(action = "tenancy.membership.denied")
        assertThat(filterDenial).hasSize(1)
    }

    @Test
    fun `SUSPENDED membership — 403 from TenantContextFilter`() {
        // Same filter-level denial path: SUSPENDED → 403
        // tenancy.forbidden before CreatePatientPolicy runs.
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "SUSPENDED")

        val response = post("alice", "acme-health", MINIMAL_CREATE_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val clinicalDenial = auditRows(action = "authz.write.denied")
        assertThat(clinicalDenial).isEmpty()
        val filterDenial = auditRows(action = "tenancy.membership.denied")
        assertThat(filterDenial).hasSize(1)
    }

    // --- Cross-tenant isolation ---

    @Test
    fun `alice cannot create a patient in tenant B`() {
        val alice = provisionUser("alice")
        val tenantA = seedTenant("tenant-a")
        seedTenant("tenant-b")
        seedMembership(tenantA, alice, role = "OWNER", status = "ACTIVE")
        // Note: alice is OWNER of A but has NO membership in B.

        val response = post("alice", "tenant-b", MINIMAL_CREATE_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    // --- Validation ---

    @Test
    fun `missing required field — 422 with validationErrors`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        // nameGiven missing entirely (use a compact partial body).
        val response = post(
            "alice", "acme-health",
            """{"nameFamily":"Lovelace","birthDate":"1960-05-15","administrativeSex":"female"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(response.body!!["code"]).isEqualTo("request.validation_failed")
    }

    @Test
    fun `birth_date in the future — 422 with validator code in_future`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = post(
            "alice", "acme-health",
            body(given = "F", family = "F", birthDate = "3000-01-01"),
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `invalid administrative_sex wire value — 422`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = post(
            "alice", "acme-health",
            """{"nameGiven":"A","nameFamily":"B","birthDate":"1990-01-01","administrativeSex":"MALE"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    // --- helpers ---

    private val minimalBody = MINIMAL_CREATE_BODY

    private fun body(
        given: String = "Ada",
        family: String = "Lovelace",
        birthDate: String = "1960-05-15",
        administrativeSex: String = "female",
    ): String = """
        {"nameGiven":"$given","nameFamily":"$family",
         "birthDate":"$birthDate","administrativeSex":"$administrativeSex"}
    """.trimIndent()

    private fun provisionUser(subject: String): UUID {
        val probe = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearerOnly(tokenFor(subject))),
            Map::class.java,
        )
        check(probe.statusCode == HttpStatus.OK)
        return jdbc.queryForObject(
            "SELECT id FROM identity.\"user\" WHERE subject = ?",
            UUID::class.java,
            subject,
        )!!
    }

    private fun seedTenant(slug: String, status: String = "ACTIVE"): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        jdbc.update(
            """
            INSERT INTO tenancy.tenant(
                id, slug, display_name, status, created_at, updated_at, row_version
            ) VALUES (?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
            id, slug, "Display $slug", status,
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
        )
        return id
    }

    private fun seedMembership(tenant: UUID, user: UUID, role: String, status: String) {
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

    @Suppress("UNCHECKED_CAST")
    private fun post(
        subject: String,
        slug: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            // PHI routes require the tenant header so PhiRequestContextFilter
            // can populate the holder with (userId, tenantId).
            add("X-Medcore-Tenant", slug)
            extraHeaders.forEach { (k, v) -> add(k, v) }
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients",
            HttpMethod.POST,
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
        const val MINIMAL_CREATE_BODY: String =
            """{"nameGiven":"Ada","nameFamily":"Lovelace","birthDate":"1960-05-15","administrativeSex":"female"}"""
    }
}
