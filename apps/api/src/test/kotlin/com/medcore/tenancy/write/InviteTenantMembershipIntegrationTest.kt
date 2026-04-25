package com.medcore.tenancy.write

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
 * `POST /api/v1/tenants/{slug}/memberships` (Phase 3J.3).
 *
 * Proves the full second-write pipeline: controller → Bean
 * Validation → domain validator → policy → transact-open →
 * TenancyRlsTxHook → handler → RLS backstop → auditor → 201.
 *
 * Also proves the ADR-007 privilege-escalation guard (ADMIN
 * cannot invite OWNER) and the structured denial-audit
 * target-user capture (resource_id = target user UUID).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class InviteTenantMembershipIntegrationTest {

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
            "/api/v1/tenants/acme-health/memberships",
            HttpMethod.POST,
            HttpEntity("""{"userId":"${UUID.randomUUID()}","role":"MEMBER"}""", jsonHeaders()),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // --- Success paths ---

    @Test
    fun `OWNER invites MEMBER — 201, membership row, audit row with intent slug`() {
        val owner = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = post("alice", "acme-health", """{"userId":"$target","role":"MEMBER"}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val data = response.body!!["data"] as Map<*, *>
        assertThat(data["userId"]).isEqualTo(target.toString())
        assertThat(data["role"]).isEqualTo("MEMBER")
        assertThat(data["status"]).isEqualTo("ACTIVE")

        val membershipCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenancy.tenant_membership WHERE tenant_id = ? AND user_id = ?",
            Int::class.java, tenant, target,
        )
        assertThat(membershipCount).isEqualTo(1)

        val audit = successAuditRows()
        assertThat(audit).hasSize(1)
        assertThat(audit.single()["action"]).isEqualTo("tenancy.membership.invited")
        assertThat(audit.single()["reason"]).isEqualTo("intent:tenancy.membership.invite")
        assertThat(audit.single()["resource_type"]).isEqualTo("tenant_membership")
    }

    @Test
    fun `OWNER invites ADMIN — 201`() {
        val owner = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = post("alice", "acme-health", """{"userId":"$target","role":"ADMIN"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `OWNER invites another OWNER — 201 (OWNER holds TENANT_DELETE)`() {
        val owner = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = post("alice", "acme-health", """{"userId":"$target","role":"OWNER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `ADMIN invites MEMBER — 201`() {
        val admin = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, admin, role = "ADMIN", status = "ACTIVE")

        val response = post("alice", "acme-health", """{"userId":"$target","role":"MEMBER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `ADMIN invites ADMIN — 201`() {
        val admin = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, admin, role = "ADMIN", status = "ACTIVE")

        val response = post("alice", "acme-health", """{"userId":"$target","role":"ADMIN"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    // --- Privilege-escalation guard ---

    @Test
    fun `ADMIN invites OWNER — 403 + denial=insufficient_authority (escalation guard)`() {
        val admin = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, admin, role = "ADMIN", status = "ACTIVE")

        val response = post("alice", "acme-health", """{"userId":"$target","role":"OWNER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body!!["code"]).isEqualTo("auth.forbidden")

        val denial = denialAuditRows(target)
        assertThat(denial).hasSize(1)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:tenancy.membership.invite|denial:insufficient_authority")
        // Structured target-user capture on denial
        assertThat(denial.single()["resource_type"]).isEqualTo("tenant_membership")
        assertThat(denial.single()["resource_id"]).isEqualTo(target.toString())
    }

    // --- Denial matrix ---

    @Test
    fun `MEMBER cannot invite — 403 + denial=insufficient_authority`() {
        val member = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, member, role = "MEMBER", status = "ACTIVE")

        val response = post("alice", "acme-health", """{"userId":"$target","role":"MEMBER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val denial = denialAuditRows(target)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:tenancy.membership.invite|denial:insufficient_authority")
    }

    @Test
    fun `non-member cannot invite — 403 + denial=not_a_member`() {
        provisionUser("alice")
        val target = provisionUser("bob")
        seedTenant("acme-health")

        val response = post("alice", "acme-health", """{"userId":"$target","role":"MEMBER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val denial = denialAuditRows(target)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:tenancy.membership.invite|denial:not_a_member")
    }

    @Test
    fun `active OWNER of suspended tenant — 403 + denial=tenant_suspended`() {
        val owner = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("paused-co", status = "SUSPENDED")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = post("alice", "paused-co", """{"userId":"$target","role":"MEMBER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val denial = denialAuditRows(target)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:tenancy.membership.invite|denial:tenant_suspended")
    }

    @Test
    fun `unknown slug — 403 + denial=not_a_member (enumeration protection)`() {
        provisionUser("alice")
        val target = provisionUser("bob")

        val response = post("alice", "does-not-exist", """{"userId":"$target","role":"MEMBER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val denial = denialAuditRows(target)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:tenancy.membership.invite|denial:not_a_member")
    }

    // --- Handler-level failures ---

    @Test
    fun `non-existent target user — 422 field=userId code=user_not_found (hides existence)`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val ghost = UUID.randomUUID()
        val response = post("alice", "acme-health", """{"userId":"$ghost","role":"MEMBER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(response.body!!["code"]).isEqualTo("request.validation_failed")

        @Suppress("UNCHECKED_CAST")
        val details = response.body!!["details"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val errors = details["validationErrors"] as List<Map<String, String>>
        assertThat(errors.single()["field"]).isEqualTo("userId")
        assertThat(errors.single()["code"]).isEqualTo("user_not_found")
    }

    @Test
    fun `duplicate membership — 409 resource_conflict (V6 unique constraint)`() {
        val owner = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")
        // Pre-seed a membership for bob so the invite conflicts.
        seedMembership(tenant, target, role = "MEMBER", status = "ACTIVE")

        val response = post("alice", "acme-health", """{"userId":"$target","role":"ADMIN"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!["code"]).isEqualTo("resource.conflict")
    }

    @Test
    fun `self-invite when caller is already member — 409 (unique constraint)`() {
        // Caller is OWNER of tenant (so has MEMBERSHIP_INVITE and
        // TENANT_DELETE). Tries to invite self as another role.
        // Policy allows (self-invite is not specifically blocked);
        // V6 unique constraint catches it.
        val alice = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, alice, role = "OWNER", status = "ACTIVE")

        val response = post("alice", "acme-health", """{"userId":"$alice","role":"ADMIN"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!["code"]).isEqualTo("resource.conflict")
    }

    // --- 422 validation ---

    @Test
    fun `missing userId — 422`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = post("alice", "acme-health", """{"role":"MEMBER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `missing role — 422`() {
        val owner = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = post("alice", "acme-health", """{"userId":"$target"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    // --- requestId parity + idempotency-key passthrough ---

    @Test
    fun `requestId in body matches X-Request-Id response header`() {
        val owner = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = post("alice", "acme-health", """{"userId":"$target","role":"MEMBER"}""")
        val headerRequestId = response.headers.getFirst("X-Request-Id")
        val bodyRequestId = response.body!!["requestId"] as String?

        assertThat(headerRequestId).isNotBlank()
        assertThat(bodyRequestId).isEqualTo(headerRequestId)
    }

    @Test
    fun `Idempotency-Key header accepted (shape-only, not deduped)`() {
        val owner = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val headers = HttpHeaders().apply {
            add(HttpHeaders.AUTHORIZATION, "Bearer ${tokenFor("alice")}")
            add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            add("Idempotency-Key", "client-retry-token-xyz")
        }
        val response = rest.exchange(
            "/api/v1/tenants/acme-health/memberships",
            HttpMethod.POST,
            HttpEntity("""{"userId":"$target","role":"MEMBER"}""", headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    // --- helpers ---

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
            INSERT INTO tenancy.tenant
                (id, slug, display_name, status, created_at, updated_at, row_version)
            VALUES (?, ?, ?, ?, ?, ?, 0)
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
            INSERT INTO tenancy.tenant_membership
                (id, tenant_id, user_id, role, status, created_at, updated_at, row_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(), tenant, user, role, status,
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
        )
    }

    private fun successAuditRows(): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT action, reason, outcome, resource_type, resource_id
              FROM audit.audit_event
             WHERE action = 'tenancy.membership.invited'
             ORDER BY recorded_at
            """.trimIndent(),
        )

    private fun denialAuditRows(targetUserId: UUID): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT action, reason, outcome, resource_type, resource_id
              FROM audit.audit_event
             WHERE action = 'authz.write.denied' AND resource_id = ?
             ORDER BY recorded_at
            """.trimIndent(),
            targetUserId.toString(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun post(
        subject: String,
        slug: String,
        body: String,
    ): ResponseEntity<Map<String, Any>> = rest.exchange(
        "/api/v1/tenants/$slug/memberships",
        HttpMethod.POST,
        HttpEntity(body, authJsonHeaders(tokenFor(subject))),
        Map::class.java,
    ) as ResponseEntity<Map<String, Any>>

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

    private fun jsonHeaders(): HttpHeaders = HttpHeaders().apply {
        add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
    }

    private fun authJsonHeaders(token: String): HttpHeaders = HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $token")
        add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
    }

    private fun bearerOnly(token: String): HttpHeaders = HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}
