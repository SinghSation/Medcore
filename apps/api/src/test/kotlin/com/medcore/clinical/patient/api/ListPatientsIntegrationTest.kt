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
 * End-to-end coverage for `GET /api/v1/tenants/{slug}/patients`
 * (Phase 4B.1, Vertical Slice 1 Chunk B). First list-shaped PHI
 * read in Medcore.
 *
 * Proves the NORMATIVE contract:
 *
 *   1. Happy path — 200 with items, totalCount, limit, offset,
 *      hasMore + exactly ONE `CLINICAL_PATIENT_LIST_ACCESSED`
 *      audit row per call.
 *   2. Pagination shape — default (limit=20, offset=0); explicit
 *      limit + offset; page-boundary navigation.
 *   3. Validation — limit out of [1..50]; negative offset;
 *      offset not a multiple of limit → 422 each.
 *   4. Empty tenant — 0 items, totalCount=0, hasMore=false + ONE
 *      audit row (empty list IS a disclosure event).
 *   5. RLS — tenant A caller cannot see tenant B's patients.
 *   6. Filter-level denials (no membership) — 403, no list-access
 *      audit row (tenancy.membership.denied instead).
 *   7. Audit row shape — reason slug format with count/limit/offset.
 *   8. All three roles (OWNER, ADMIN, MEMBER) can list.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class ListPatientsIntegrationTest {

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

    // --- 401 ---

    @Test
    fun `LIST without bearer token returns 401`() {
        val response = rest.exchange(
            "/api/v1/tenants/acme-health/patients",
            HttpMethod.GET,
            HttpEntity<Void>(jsonHeaders()),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // --- Success paths ---

    @Test
    fun `OWNER lists 3 patients — 200 + ONE CLINICAL_PATIENT_LIST_ACCESSED audit row`() {
        seedUserWithTenant("alice", role = "OWNER")
        postPatient("alice", "acme-health", given = "Ada", family = "Lovelace")
        postPatient("alice", "acme-health", given = "Grace", family = "Hopper")
        postPatient("alice", "acme-health", given = "Katherine", family = "Johnson")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = list("alice", "acme-health")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val data = response.body!!["data"] as Map<*, *>
        val items = data["items"] as List<*>
        assertThat(items).hasSize(3)
        assertThat(data["totalCount"]).isEqualTo(3)
        assertThat(data["limit"]).isEqualTo(20)
        assertThat(data["offset"]).isEqualTo(0)
        assertThat(data["hasMore"]).isEqualTo(false)

        // NORMATIVE audit-row shape — ONE row per call.
        val audit = auditRows("clinical.patient.list_accessed")
        assertThat(audit).hasSize(1)
        val row = audit.single()
        assertThat(row["reason"])
            .isEqualTo("intent:clinical.patient.list|count:3|limit:20|offset:0")
        assertThat(row["resource_type"]).isEqualTo("clinical.patient")
        assertThat(row["resource_id"])
            .describedAs("list has no single target row")
            .isNull()
        assertThat(row["outcome"]).isEqualTo("SUCCESS")
        assertThat(row["tenant_id"]).isNotNull()
    }

    @Test
    fun `list item wire shape is summary-only (id, mrn, name, DOB, sex, createdAt)`() {
        seedUserWithTenant("alice", role = "OWNER")
        postPatient("alice", "acme-health", given = "Ada", family = "Lovelace")

        val response = list("alice", "acme-health")
        val data = response.body!!["data"] as Map<*, *>
        val first = (data["items"] as List<*>).single() as Map<*, *>

        assertThat(first.keys).containsExactlyInAnyOrder(
            "id", "mrn", "nameGiven", "nameFamily",
            "birthDate", "administrativeSex", "createdAt",
        )
        assertThat(first["nameGiven"]).isEqualTo("Ada")
        assertThat(first["nameFamily"]).isEqualTo("Lovelace")
        assertThat(first["administrativeSex"]).isEqualTo("female")
    }

    @Test
    fun `explicit limit=2 offset=0 returns 2 items, hasMore=true, totalCount=3`() {
        seedUserWithTenant("alice", role = "OWNER")
        postPatient("alice", "acme-health", given = "Ada", family = "Lovelace")
        postPatient("alice", "acme-health", given = "Grace", family = "Hopper")
        postPatient("alice", "acme-health", given = "Katherine", family = "Johnson")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = list("alice", "acme-health", limit = 2, offset = 0)

        val data = response.body!!["data"] as Map<*, *>
        assertThat((data["items"] as List<*>)).hasSize(2)
        assertThat(data["totalCount"]).isEqualTo(3)
        assertThat(data["hasMore"]).isEqualTo(true)

        val row = auditRows("clinical.patient.list_accessed").single()
        assertThat(row["reason"])
            .isEqualTo("intent:clinical.patient.list|count:2|limit:2|offset:0")
    }

    @Test
    fun `limit=2 offset=2 returns the tail page, hasMore=false`() {
        seedUserWithTenant("alice", role = "OWNER")
        postPatient("alice", "acme-health", given = "Ada", family = "Lovelace")
        postPatient("alice", "acme-health", given = "Grace", family = "Hopper")
        postPatient("alice", "acme-health", given = "Katherine", family = "Johnson")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = list("alice", "acme-health", limit = 2, offset = 2)

        val data = response.body!!["data"] as Map<*, *>
        assertThat((data["items"] as List<*>)).hasSize(1)
        assertThat(data["totalCount"]).isEqualTo(3)
        assertThat(data["hasMore"]).isEqualTo(false)
        assertThat(data["offset"]).isEqualTo(2)

        val row = auditRows("clinical.patient.list_accessed").single()
        assertThat(row["reason"])
            .isEqualTo("intent:clinical.patient.list|count:1|limit:2|offset:2")
    }

    @Test
    fun `empty tenant — 0 items, count=0, hasMore=false, still ONE audit row`() {
        seedUserWithTenant("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = list("alice", "acme-health")

        val data = response.body!!["data"] as Map<*, *>
        assertThat((data["items"] as List<*>)).isEmpty()
        assertThat(data["totalCount"]).isEqualTo(0)
        assertThat(data["hasMore"]).isEqualTo(false)

        val audit = auditRows("clinical.patient.list_accessed")
        assertThat(audit)
            .describedAs("empty list IS a disclosure event — still audited")
            .hasSize(1)
        assertThat(audit.single()["reason"])
            .isEqualTo("intent:clinical.patient.list|count:0|limit:20|offset:0")
    }

    @Test
    fun `MEMBER can list — PATIENT_READ granted to all roles per 4A1 role map`() {
        val owner = provisionUser("owner")
        val member = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, member, role = "MEMBER")
        postPatient("owner", "acme-health", given = "Ada", family = "Lovelace")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = list("alice", "acme-health")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val data = response.body!!["data"] as Map<*, *>
        assertThat((data["items"] as List<*>)).hasSize(1)
        assertThat(auditRows("clinical.patient.list_accessed")).hasSize(1)
    }

    @Test
    fun `ADMIN can list — 200`() {
        val owner = provisionUser("owner")
        val admin = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, admin, role = "ADMIN")
        postPatient("owner", "acme-health", given = "Ada", family = "Lovelace")

        val response = list("alice", "acme-health")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    // --- Ordering ---

    @Test
    fun `items ordered by createdAt DESC (newest first)`() {
        // Seed 3 patients via the admin datasource with explicit
        // distinct timestamps so this assertion is not timing-
        // sensitive. The production ordering invariant
        // (created_at DESC, id DESC) is the subject under test,
        // not clock resolution.
        val user = seedUserWithTenant("alice", role = "OWNER")
        val tenantId = jdbc.queryForObject(
            "SELECT id FROM tenancy.tenant WHERE slug = 'acme-health'",
            UUID::class.java,
        )!!
        insertPatientAdmin(
            tenantId = tenantId,
            user = user,
            mrn = "MRN-OLD",
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
        insertPatientAdmin(
            tenantId = tenantId,
            user = user,
            mrn = "MRN-MID",
            createdAt = Instant.parse("2026-02-01T00:00:00Z"),
        )
        insertPatientAdmin(
            tenantId = tenantId,
            user = user,
            mrn = "MRN-NEW",
            createdAt = Instant.parse("2026-03-01T00:00:00Z"),
        )
        jdbc.update("DELETE FROM audit.audit_event")

        val response = list("alice", "acme-health")
        val items = (response.body!!["data"] as Map<*, *>)["items"] as List<*>

        assertThat(items).hasSize(3)
        assertThat((items[0] as Map<*, *>)["mrn"]).isEqualTo("MRN-NEW")
        assertThat((items[1] as Map<*, *>)["mrn"]).isEqualTo("MRN-MID")
        assertThat((items[2] as Map<*, *>)["mrn"]).isEqualTo("MRN-OLD")
    }

    /**
     * Admin-bypass insert. Uses the admin datasource (superuser)
     * to sidestep RLS WITH CHECK and set explicit timestamps.
     * Tests only.
     */
    private fun insertPatientAdmin(
        tenantId: UUID,
        user: UUID,
        mrn: String,
        createdAt: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO clinical.patient(
                id, tenant_id, mrn, mrn_source,
                name_given, name_family, birth_date,
                administrative_sex, status,
                created_at, updated_at, created_by, updated_by, row_version
            ) VALUES (?, ?, ?, 'GENERATED',
                      'X', 'Y', DATE '1960-05-15',
                      'female', 'ACTIVE',
                      ?, ?, ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(), tenantId, mrn,
            java.sql.Timestamp.from(createdAt),
            java.sql.Timestamp.from(createdAt),
            user, user,
        )
    }

    // --- Validation (422) ---

    @Test
    fun `limit=0 returns 422 request_validation_failed`() {
        seedUserWithTenant("alice", role = "OWNER")
        val response = list("alice", "acme-health", limit = 0, offset = 0)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `limit=51 returns 422 (exceeds MAX_LIMIT)`() {
        seedUserWithTenant("alice", role = "OWNER")
        val response = list("alice", "acme-health", limit = 51, offset = 0)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `negative offset returns 422`() {
        seedUserWithTenant("alice", role = "OWNER")
        val response = list("alice", "acme-health", limit = 20, offset = -1)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `non-page-boundary offset returns 422 (offset must be multiple of limit)`() {
        seedUserWithTenant("alice", role = "OWNER")
        val response = list("alice", "acme-health", limit = 10, offset = 3)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `validation failure emits NO list-access audit row (no gate was opened)`() {
        seedUserWithTenant("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        list("alice", "acme-health", limit = -1, offset = 0)

        assertThat(auditRows("clinical.patient.list_accessed"))
            .describedAs("validation fires BEFORE ReadGate — no audit")
            .isEmpty()
    }

    // --- Cross-tenant RLS ---

    @Test
    fun `tenant A patients are invisible to tenant B's list under RLS`() {
        val alice = provisionUser("alice")
        val bob = provisionUser("bob")
        val tenantA = seedTenant("tenant-a")
        val tenantB = seedTenant("tenant-b")
        seedMembership(tenantA, alice, role = "OWNER")
        seedMembership(tenantB, bob, role = "OWNER")

        // Alice puts 2 patients in tenant A.
        postPatient("alice", "tenant-a", given = "Ada", family = "Lovelace")
        postPatient("alice", "tenant-a", given = "Grace", family = "Hopper")
        jdbc.update("DELETE FROM audit.audit_event")

        // Bob lists tenant B — expects zero.
        val response = list("bob", "tenant-b")
        val data = response.body!!["data"] as Map<*, *>
        assertThat((data["items"] as List<*>))
            .describedAs("cross-tenant rows are filtered by V14 p_patient_select")
            .isEmpty()
        assertThat(data["totalCount"]).isEqualTo(0)
    }

    // --- Filter-level denials (no CLINICAL_PATIENT_LIST_ACCESSED) ---

    @Test
    fun `caller with no membership — 403, no list-access audit, tenancy-denied audit instead`() {
        provisionUser("alice")
        val owner = provisionUser("owner")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = list("alice", "acme-health")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        assertThat(auditRows("clinical.patient.list_accessed")).isEmpty()
        assertThat(auditRows("tenancy.membership.denied")).hasSize(1)
    }

    @Test
    fun `SUSPENDED membership — 403`() {
        val owner = provisionUser("owner")
        val alice = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, alice, role = "OWNER", status = "SUSPENDED")

        val response = list("alice", "acme-health")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(auditRows("clinical.patient.list_accessed")).isEmpty()
    }

    // --- helpers ---

    private fun seedUserWithTenant(
        subject: String,
        role: String = "OWNER",
    ): UUID {
        val user = provisionUser(subject)
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, user, role = role)
        return user
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
    private fun postPatient(
        subject: String,
        slug: String,
        given: String,
        family: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients",
            HttpMethod.POST,
            HttpEntity(
                """
                {"nameGiven":"$given","nameFamily":"$family",
                 "birthDate":"1960-05-15","administrativeSex":"female"}
                """.trimIndent(),
                headers,
            ),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun list(
        subject: String,
        slug: String,
        limit: Int? = null,
        offset: Int? = null,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        val query = buildList {
            if (limit != null) add("limit=$limit")
            if (offset != null) add("offset=$offset")
        }.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients$query",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    private fun auditRows(action: String): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT action, reason, outcome, resource_type, resource_id, tenant_id
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
