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
 * `GET /api/v1/tenants/{slug}/patients/{patientId}/encounters`
 * (Phase 4C.3).
 *
 * Proves:
 *   1. Happy path: 200 + items array (newest first) + ONE
 *      `CLINICAL_ENCOUNTER_LIST_ACCESSED` audit row with reason
 *      `"intent:clinical.encounter.list|count:N"`.
 *   2. Empty list: 200 with items=[] + ONE audit row (count:0).
 *   3. All three roles can read (ENCOUNTER_READ granted
 *      universally per 4C.1 role map).
 *   4. 404 on unknown patientId.
 *   5. 404 on cross-tenant patient (no existence leak).
 *   6. Filter-level denial (no membership) — 403 + no list
 *      audit row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class ListPatientEncountersIntegrationTest {

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
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `OWNER lists 3 encounters newest-first — 200 + ONE list-access audit row`() {
        val (_, patientId) = seedPatient("alice", role = "OWNER")
        // Phase 4C.4 invariant: at most one IN_PROGRESS encounter
        // per (tenant, patient). Cancel each encounter before
        // opening the next so all three rows co-exist; closed rows
        // still list.
        val e1 = createEncounterHttp("alice", "acme-health", patientId)
        cancelEncounterHelper("alice", e1, "NO_SHOW")
        Thread.sleep(5)
        val e2 = createEncounterHttp("alice", "acme-health", patientId)
        cancelEncounterHelper("alice", e2, "NO_SHOW")
        Thread.sleep(5)
        val e3 = createEncounterHttp("alice", "acme-health", patientId)
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = get("alice", "acme-health", patientId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val data = resp.body!!["data"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val items = data["items"] as List<Map<String, Any>>
        assertThat(items).hasSize(3)
        // Newest-first: e3, e2, e1.
        assertThat(items[0]["id"]).isEqualTo(e3.toString())
        assertThat(items[1]["id"]).isEqualTo(e2.toString())
        assertThat(items[2]["id"]).isEqualTo(e1.toString())

        val row = auditRows("clinical.encounter.list_accessed").single()
        assertThat(row["reason"])
            .isEqualTo("intent:clinical.encounter.list|count:3")
        assertThat(row["resource_type"]).isEqualTo("clinical.encounter")
        assertThat(row["resource_id"]).isNull()
        assertThat(row["outcome"]).isEqualTo("SUCCESS")
    }

    @Test
    fun `empty list still emits ONE list-access audit with count=0`() {
        val (_, patientId) = seedPatient("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = get("alice", "acme-health", patientId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val items = ((resp.body!!["data"] as Map<String, Any>)["items"]) as List<*>
        assertThat(items).isEmpty()

        val row = auditRows("clinical.encounter.list_accessed").single()
        assertThat(row["reason"])
            .isEqualTo("intent:clinical.encounter.list|count:0")
    }

    @Test
    fun `MEMBER can list encounters — 200 (ENCOUNTER_READ granted to all roles)`() {
        val owner = provisionUser("owner")
        val member = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, member, role = "MEMBER")
        val patientId = createPatient("owner", "acme-health")
        createEncounterHttp("owner", "acme-health", patientId)

        val resp = get("alice", "acme-health", patientId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `unknown patientId — 404 with NO list-access audit`() {
        val (_, _) = seedPatient("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = get("alice", "acme-health", UUID.randomUUID())
        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.encounter.list_accessed")).isEmpty()
    }

    @Test
    fun `cross-tenant patient — 404, no audit, no leak`() {
        val alice = provisionUser("alice")
        val tenantA = seedTenant("tenant-a")
        val tenantB = seedTenant("tenant-b")
        seedMembership(tenantA, alice, role = "OWNER")
        seedMembership(tenantB, alice, role = "OWNER")
        val patientInA = createPatient("alice", "tenant-a")
        createEncounterHttp("alice", "tenant-a", patientInA)
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = get("alice", "tenant-b", patientInA)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.encounter.list_accessed")).isEmpty()
    }

    @Test
    fun `caller with no membership — 403 from TenantContextFilter`() {
        provisionUser("alice")
        val owner = provisionUser("owner")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        val patientId = createPatient("owner", "acme-health")
        createEncounterHttp("owner", "acme-health", patientId)
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = get("alice", "acme-health", patientId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(auditRows("clinical.encounter.list_accessed")).isEmpty()
    }

    // ---- helpers ----

    private fun seedPatient(subject: String, role: String = "OWNER"): Pair<UUID, UUID> {
        val user = provisionUser(subject)
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, user, role = role)
        val patientId = createPatient(subject, "acme-health")
        return user to patientId
    }

    private fun createPatient(subject: String, slug: String): UUID {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        val resp = rest.exchange(
            "/api/v1/tenants/$slug/patients",
            HttpMethod.POST,
            HttpEntity(
                """{"nameGiven":"Ada","nameFamily":"Lovelace","birthDate":"1960-05-15","administrativeSex":"female"}""",
                headers,
            ),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val data = resp.body!!["data"] as Map<String, Any>
        return UUID.fromString(data["id"] as String)
    }

    private fun createEncounterHttp(subject: String, slug: String, patientId: UUID): UUID {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        val resp = rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId/encounters",
            HttpMethod.POST,
            HttpEntity("""{"encounterClass":"AMB"}""", headers),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val data = resp.body!!["data"] as Map<String, Any>
        return UUID.fromString(data["id"] as String)
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
            "/api/v1/tenants/$slug/patients/$patientId/encounters",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
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

    // 4C.4 helper — cancel an encounter so another can be started
    // on the same patient without tripping the per-patient
    // IN_PROGRESS uniqueness invariant (V22).
    private fun cancelEncounterHelper(
        subject: String,
        encounterId: UUID,
        reason: String,
    ) {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", "acme-health")
        }
        rest.exchange(
            "/api/v1/tenants/acme-health/encounters/$encounterId/cancel",
            HttpMethod.POST,
            HttpEntity("""{"cancelReason":"$reason"}""", headers),
            Map::class.java,
        )
    }
}
