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
 * `PATCH /api/v1/tenants/{slug}/memberships/{membershipId}`
 * (Phase 3J.N). Proves:
 *
 * - Base authority enforcement (MEMBERSHIP_ROLE_UPDATE)
 * - Escalation guard (only OWNER can promote to OWNER)
 * - Target-OWNER guard (ADMIN cannot modify OWNER)
 * - Last-OWNER invariant (cannot demote the last active OWNER)
 * - No-op suppression (same-role PATCH emits no audit row)
 * - Cross-tenant ID probing masked as 404
 * - Audit-row shape contract (3J.N extension of 3J.3 discipline)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class UpdateTenantMembershipRoleIntegrationTest {

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
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    // --- 401 ---

    @Test
    fun `PATCH without bearer — 401`() {
        val response = rest.exchange(
            "/api/v1/tenants/acme-health/memberships/${UUID.randomUUID()}",
            HttpMethod.PATCH,
            HttpEntity("""{"role":"MEMBER"}""", jsonHeaders()),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // --- Success: OWNER operations ---

    @Test
    fun `OWNER promotes MEMBER to ADMIN — 200 + audit from=MEMBER|to=ADMIN`() {
        val owner = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")
        val targetMembership = seedMembership(tenant, target, role = "MEMBER", status = "ACTIVE")

        val response = patch("alice", "acme-health", targetMembership, """{"role":"ADMIN"}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val data = response.body!!["data"] as Map<*, *>
        assertThat(data["role"]).isEqualTo("ADMIN")
        assertThat(currentRole(targetMembership)).isEqualTo("ADMIN")

        val audit = roleUpdateAudit(targetMembership)
        assertThat(audit).hasSize(1)
        assertThat(audit.single()["reason"])
            .isEqualTo("intent:tenancy.membership.update_role|from:MEMBER|to:ADMIN")
    }

    @Test
    fun `OWNER promotes MEMBER to OWNER — 200`() {
        val owner = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")
        val targetMembership = seedMembership(tenant, target, role = "MEMBER", status = "ACTIVE")

        val response = patch("alice", "acme-health", targetMembership, """{"role":"OWNER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(currentRole(targetMembership)).isEqualTo("OWNER")
    }

    @Test
    fun `OWNER demotes another OWNER (multi-owner tenant) — 200`() {
        val alice = provisionUser("alice")
        val bob = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, alice, role = "OWNER", status = "ACTIVE")
        val bobMembership = seedMembership(tenant, bob, role = "OWNER", status = "ACTIVE")

        val response = patch("alice", "acme-health", bobMembership, """{"role":"ADMIN"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    // --- Success: ADMIN operations ---

    @Test
    fun `ADMIN demotes another ADMIN to MEMBER — 200 (peer-demote allowed)`() {
        val admin = provisionUser("alice")
        val peer = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, admin, role = "ADMIN", status = "ACTIVE")
        val peerMembership = seedMembership(tenant, peer, role = "ADMIN", status = "ACTIVE")

        val response = patch("alice", "acme-health", peerMembership, """{"role":"MEMBER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    // --- Escalation guard ---

    @Test
    fun `ADMIN promotes MEMBER to OWNER — 403 + denial=insufficient_authority`() {
        val admin = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, admin, role = "ADMIN", status = "ACTIVE")
        val targetMembership = seedMembership(tenant, target, role = "MEMBER", status = "ACTIVE")

        val response = patch("alice", "acme-health", targetMembership, """{"role":"OWNER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val denial = denialAudit(targetMembership)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:tenancy.membership.update_role|denial:insufficient_authority")
    }

    // --- Target-OWNER guard ---

    @Test
    fun `ADMIN modifies OWNER — 403 + target-OWNER guard`() {
        val admin = provisionUser("alice")
        val victim = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, admin, role = "ADMIN", status = "ACTIVE")
        val ownerMembership = seedMembership(tenant, victim, role = "OWNER", status = "ACTIVE")

        val response = patch("alice", "acme-health", ownerMembership, """{"role":"ADMIN"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val denial = denialAudit(ownerMembership)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:tenancy.membership.update_role|denial:insufficient_authority")
    }

    // --- Last-OWNER invariant (the architectural core of 3J.N) ---

    @Test
    fun `sole OWNER demotes self to ADMIN — 409 last_owner_in_tenant`() {
        val alice = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        val aliceMembership = seedMembership(tenant, alice, role = "OWNER", status = "ACTIVE")

        val response = patch("alice", "acme-health", aliceMembership, """{"role":"ADMIN"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!["code"]).isEqualTo("resource.conflict")

        @Suppress("UNCHECKED_CAST")
        val details = response.body!!["details"] as Map<String, Any>
        assertThat(details["reason"]).isEqualTo("last_owner_in_tenant")

        // DB unchanged
        assertThat(currentRole(aliceMembership)).isEqualTo("OWNER")
    }

    @Test
    fun `sole OWNER demotes self to MEMBER — 409`() {
        val alice = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        val aliceMembership = seedMembership(tenant, alice, role = "OWNER", status = "ACTIVE")

        val response = patch("alice", "acme-health", aliceMembership, """{"role":"MEMBER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `OWNER demotes co-OWNER when 2 OWNERs exist — succeeds`() {
        val alice = provisionUser("alice")
        val bob = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, alice, role = "OWNER", status = "ACTIVE")
        val bobMembership = seedMembership(tenant, bob, role = "OWNER", status = "ACTIVE")

        val response = patch("alice", "acme-health", bobMembership, """{"role":"MEMBER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(countActiveOwners(tenant)).isEqualTo(1)
    }

    @Test
    fun `SUSPENDED OWNER does not count toward active-OWNER floor`() {
        // Alice is the ONLY ACTIVE OWNER; Bob is SUSPENDED OWNER.
        // Demoting Alice should still trigger last-OWNER 409
        // because SUSPENDED memberships are not active.
        val alice = provisionUser("alice")
        val bob = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        val aliceMembership = seedMembership(tenant, alice, role = "OWNER", status = "ACTIVE")
        seedMembership(tenant, bob, role = "OWNER", status = "SUSPENDED")

        val response = patch("alice", "acme-health", aliceMembership, """{"role":"ADMIN"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    // --- No-op suppression ---

    @Test
    fun `OWNER sets MEMBER to MEMBER (no-op) — 200 no audit row`() {
        val alice = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, alice, role = "OWNER", status = "ACTIVE")
        val targetMembership = seedMembership(tenant, target, role = "MEMBER", status = "ACTIVE")

        val response = patch("alice", "acme-health", targetMembership, """{"role":"MEMBER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(roleUpdateAudit(targetMembership)).isEmpty()
    }

    // --- Denial matrix ---

    @Test
    fun `MEMBER attempts PATCH — 403`() {
        val caller = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, caller, role = "MEMBER", status = "ACTIVE")
        val targetMembership = seedMembership(tenant, target, role = "MEMBER", status = "ACTIVE")

        val response = patch("alice", "acme-health", targetMembership, """{"role":"ADMIN"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `non-member attempts PATCH — 403 + denial=not_a_member`() {
        provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        val targetMembership = seedMembership(tenant, target, role = "MEMBER", status = "ACTIVE")

        val response = patch("alice", "acme-health", targetMembership, """{"role":"ADMIN"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val denial = denialAudit(targetMembership)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:tenancy.membership.update_role|denial:not_a_member")
    }

    @Test
    fun `unknown slug — 403 + denial=not_a_member`() {
        provisionUser("alice")
        val target = provisionUser("bob")
        val otherTenant = seedTenant("other-tenant")
        val targetMembership = seedMembership(otherTenant, target, role = "MEMBER", status = "ACTIVE")

        val response = patch("alice", "does-not-exist", targetMembership, """{"role":"ADMIN"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    // --- Handler-layer 404 ---

    @Test
    fun `unknown membership id — 404 resource_not_found`() {
        val alice = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, alice, role = "OWNER", status = "ACTIVE")

        val response = patch("alice", "acme-health", UUID.randomUUID(), """{"role":"ADMIN"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["code"]).isEqualTo("resource.not_found")
    }

    @Test
    fun `cross-tenant membership id — 404 (masks existence)`() {
        // Alice is OWNER of tenantA; membership belongs to tenantB.
        // Alice has authority in tenantA but the membership id is
        // for a different tenant. Must return 404, not 200 or 403.
        val alice = provisionUser("alice")
        val bob = provisionUser("bob")
        val tenantA = seedTenant("acme-health")
        val tenantB = seedTenant("other-tenant")
        seedMembership(tenantA, alice, role = "OWNER", status = "ACTIVE")
        val bobInB = seedMembership(tenantB, bob, role = "MEMBER", status = "ACTIVE")

        val response = patch("alice", "acme-health", bobInB, """{"role":"ADMIN"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    // --- Validation ---

    @Test
    fun `missing role body — 422`() {
        val alice = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, alice, role = "OWNER", status = "ACTIVE")
        val targetMembership = seedMembership(tenant, target, role = "MEMBER", status = "ACTIVE")

        val response = patch("alice", "acme-health", targetMembership, """{}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    // --- requestId parity ---

    @Test
    fun `requestId in body matches X-Request-Id`() {
        val alice = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, alice, role = "OWNER", status = "ACTIVE")
        val targetMembership = seedMembership(tenant, target, role = "MEMBER", status = "ACTIVE")

        val response = patch("alice", "acme-health", targetMembership, """{"role":"ADMIN"}""")
        val headerRequestId = response.headers.getFirst("X-Request-Id")
        val bodyRequestId = response.body!!["requestId"] as String?
        assertThat(headerRequestId).isNotBlank()
        assertThat(bodyRequestId).isEqualTo(headerRequestId)
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

    private fun seedMembership(
        tenant: UUID,
        user: UUID,
        role: String,
        status: String,
    ): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        jdbc.update(
            """
            INSERT INTO tenancy.tenant_membership
                (id, tenant_id, user_id, role, status, created_at, updated_at, row_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
            id, tenant, user, role, status,
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
        )
        return id
    }

    private fun currentRole(membershipId: UUID): String =
        jdbc.queryForObject(
            "SELECT role FROM tenancy.tenant_membership WHERE id = ?",
            String::class.java,
            membershipId,
        )!!

    private fun countActiveOwners(tenantId: UUID): Int =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM tenancy.tenant_membership
             WHERE tenant_id = ? AND role = 'OWNER' AND status = 'ACTIVE'
            """.trimIndent(),
            Int::class.java,
            tenantId,
        ) ?: 0

    private fun roleUpdateAudit(membershipId: UUID): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT action, reason, outcome, resource_type, resource_id
              FROM audit.audit_event
             WHERE action = 'tenancy.membership.role_updated' AND resource_id = ?
             ORDER BY recorded_at
            """.trimIndent(),
            membershipId.toString(),
        )

    private fun denialAudit(membershipId: UUID): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT action, reason, outcome, resource_type, resource_id
              FROM audit.audit_event
             WHERE action = 'authz.write.denied' AND resource_id = ?
             ORDER BY recorded_at
            """.trimIndent(),
            membershipId.toString(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun patch(
        subject: String,
        slug: String,
        membershipId: UUID,
        body: String,
    ): ResponseEntity<Map<String, Any>> = rest.exchange(
        "/api/v1/tenants/$slug/memberships/$membershipId",
        HttpMethod.PATCH,
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
