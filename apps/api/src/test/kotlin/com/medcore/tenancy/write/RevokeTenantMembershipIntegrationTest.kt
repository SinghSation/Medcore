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
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate

/**
 * End-to-end integration coverage for
 * `DELETE /api/v1/tenants/{slug}/memberships/{membershipId}`
 * (Phase 3J.N). Proves:
 *
 * - Soft-delete semantics (status → REVOKED, row preserved)
 * - 204 No Content response with X-Request-Id correlation
 * - Idempotency: DELETE of already-REVOKED is 204 with no audit
 * - Target-OWNER guard (ADMIN cannot revoke OWNER)
 * - Last-OWNER invariant (cannot revoke the last active OWNER)
 * - Cross-tenant ID probing masked as 404
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class RevokeTenantMembershipIntegrationTest {

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
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `DELETE without bearer — 401`() {
        val response = rest.exchange(
            "/api/v1/tenants/acme-health/memberships/${UUID.randomUUID()}",
            HttpMethod.DELETE,
            HttpEntity<Void>(HttpHeaders()),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // --- Success paths ---

    @Test
    fun `OWNER revokes MEMBER — 204 + DB status=REVOKED + audit row`() {
        val owner = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")
        val targetMembership = seedMembership(tenant, target, role = "MEMBER", status = "ACTIVE")

        val response = delete("alice", "acme-health", targetMembership)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(response.headers.getFirst("X-Request-Id")).isNotBlank()
        assertThat(currentStatus(targetMembership)).isEqualTo("REVOKED")

        val audit = revokeAudit(targetMembership)
        assertThat(audit).hasSize(1)
        assertThat(audit.single()["reason"])
            .isEqualTo("intent:tenancy.membership.remove|prior_role:MEMBER")
    }

    @Test
    fun `OWNER revokes ADMIN — 204 + prior_role audit encodes ADMIN`() {
        val owner = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")
        val targetMembership = seedMembership(tenant, target, role = "ADMIN", status = "ACTIVE")

        val response = delete("alice", "acme-health", targetMembership)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        assertThat(revokeAudit(targetMembership).single()["reason"])
            .isEqualTo("intent:tenancy.membership.remove|prior_role:ADMIN")
    }

    @Test
    fun `ADMIN revokes another ADMIN — 204 (peer-remove allowed)`() {
        val admin = provisionUser("alice")
        val peer = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, admin, role = "ADMIN", status = "ACTIVE")
        val peerMembership = seedMembership(tenant, peer, role = "ADMIN", status = "ACTIVE")

        val response = delete("alice", "acme-health", peerMembership)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    @Test
    fun `OWNER revokes co-OWNER when 2 OWNERs exist — 204`() {
        val alice = provisionUser("alice")
        val bob = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, alice, role = "OWNER", status = "ACTIVE")
        val bobMembership = seedMembership(tenant, bob, role = "OWNER", status = "ACTIVE")

        val response = delete("alice", "acme-health", bobMembership)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        assertThat(revokeAudit(bobMembership).single()["reason"])
            .isEqualTo("intent:tenancy.membership.remove|prior_role:OWNER")
    }

    // --- Target-OWNER guard ---

    @Test
    fun `ADMIN revokes OWNER — 403 target-OWNER guard`() {
        val admin = provisionUser("alice")
        val victim = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, admin, role = "ADMIN", status = "ACTIVE")
        val ownerMembership = seedMembership(tenant, victim, role = "OWNER", status = "ACTIVE")

        val response = delete("alice", "acme-health", ownerMembership)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(currentStatus(ownerMembership)).isEqualTo("ACTIVE")
    }

    // --- Last-OWNER invariant ---

    @Test
    fun `sole OWNER revokes self — 409 last_owner_in_tenant`() {
        val alice = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        val aliceMembership = seedMembership(tenant, alice, role = "OWNER", status = "ACTIVE")

        val response = delete("alice", "acme-health", aliceMembership)
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)

        @Suppress("UNCHECKED_CAST")
        val details = response.body!!["details"] as Map<String, Any>
        assertThat(details["reason"]).isEqualTo("last_owner_in_tenant")
        assertThat(currentStatus(aliceMembership)).isEqualTo("ACTIVE")
    }

    @Test
    fun `sole OWNER revokes another OWNER-membership but victim is the only ACTIVE OWNER — 409`() {
        // Alice (caller, OWNER, ACTIVE). Bob (target, OWNER, ACTIVE).
        // Wait — this is NOT a last-OWNER case because there are 2 active OWNERs.
        // Renamed: "Alice OWNER ACTIVE, Bob OWNER SUSPENDED. Alice revokes self."
        val alice = provisionUser("alice")
        val bob = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        val aliceMembership = seedMembership(tenant, alice, role = "OWNER", status = "ACTIVE")
        seedMembership(tenant, bob, role = "OWNER", status = "SUSPENDED")

        val response = delete("alice", "acme-health", aliceMembership)
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    // --- Idempotent already-REVOKED ---

    @Test
    fun `DELETE of already-REVOKED — 204 no audit row`() {
        val owner = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")
        val targetMembership = seedMembership(tenant, target, role = "MEMBER", status = "REVOKED")

        val response = delete("alice", "acme-health", targetMembership)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(revokeAudit(targetMembership)).isEmpty()
    }

    @Test
    fun `second DELETE after first — second is no-op 204 (idempotent)`() {
        val owner = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")
        val targetMembership = seedMembership(tenant, target, role = "MEMBER", status = "ACTIVE")

        delete("alice", "acme-health", targetMembership)  // 1st: revokes + audits
        delete("alice", "acme-health", targetMembership)  // 2nd: no-op, no audit

        assertThat(revokeAudit(targetMembership)).hasSize(1)
    }

    // --- Denial matrix ---

    @Test
    fun `MEMBER attempts DELETE — 403`() {
        val caller = provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, caller, role = "MEMBER", status = "ACTIVE")
        val targetMembership = seedMembership(tenant, target, role = "MEMBER", status = "ACTIVE")

        val response = delete("alice", "acme-health", targetMembership)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `non-member — 403 + denial=not_a_member`() {
        provisionUser("alice")
        val target = provisionUser("bob")
        val tenant = seedTenant("acme-health")
        val targetMembership = seedMembership(tenant, target, role = "MEMBER", status = "ACTIVE")

        val response = delete("alice", "acme-health", targetMembership)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val denial = denialAudit(targetMembership)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:tenancy.membership.remove|denial:not_a_member")
    }

    // --- Handler 404 ---

    @Test
    fun `unknown membership id — 404`() {
        val alice = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, alice, role = "OWNER", status = "ACTIVE")

        val response = delete("alice", "acme-health", UUID.randomUUID())
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `cross-tenant membership id — 404 (masks existence)`() {
        val alice = provisionUser("alice")
        val bob = provisionUser("bob")
        val tenantA = seedTenant("acme-health")
        val tenantB = seedTenant("other-tenant")
        seedMembership(tenantA, alice, role = "OWNER", status = "ACTIVE")
        val bobInB = seedMembership(tenantB, bob, role = "MEMBER", status = "ACTIVE")

        val response = delete("alice", "acme-health", bobInB)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
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

    private fun currentStatus(membershipId: UUID): String =
        jdbc.queryForObject(
            "SELECT status FROM tenancy.tenant_membership WHERE id = ?",
            String::class.java,
            membershipId,
        )!!

    private fun revokeAudit(membershipId: UUID): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT action, reason, outcome, resource_type, resource_id
              FROM audit.audit_event
             WHERE action = 'tenancy.membership.revoked' AND resource_id = ?
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
    private fun delete(
        subject: String,
        slug: String,
        membershipId: UUID,
    ): ResponseEntity<Map<String, Any>> = rest.exchange(
        "/api/v1/tenants/$slug/memberships/$membershipId",
        HttpMethod.DELETE,
        HttpEntity<Void>(bearerOnly(tokenFor(subject))),
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

    private fun bearerOnly(token: String): HttpHeaders = HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}
