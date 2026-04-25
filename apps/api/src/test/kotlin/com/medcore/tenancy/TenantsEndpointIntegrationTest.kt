package com.medcore.tenancy

import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Integration tests for `GET /api/v1/tenants` and
 * `GET /api/v1/tenants/{slug}/me`. Uses the same self-contained harness
 * as 3A.3: Testcontainers Postgres + in-process mock-oauth2-server.
 *
 * Tenancy fixtures are seeded in SQL local to each test. Identity rows are
 * created implicitly by the first `/me` call per subject so the
 * `(issuer, subject)` → `identity.user.id` mapping is the same one the
 * production JIT path produces.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class TenantsEndpointIntegrationTest {

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
    fun `GET tenants without bearer token returns 401`() {
        val response = rest.getForEntity("/api/v1/tenants", String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `GET tenants slug me without bearer token returns 401`() {
        val response = rest.getForEntity("/api/v1/tenants/any-slug/me", String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `GET tenants lists only caller's ACTIVE memberships on ACTIVE tenants`() {
        val alice = provisionUser(subject = "alice")
        val bob = provisionUser(subject = "bob")

        val acme = seedTenant(slug = "acme-health", displayName = "Acme Health")
        val beta = seedTenant(slug = "beta-clinic", displayName = "Beta Clinic")
        val archived = seedTenant(slug = "gone-corp", displayName = "Gone Corp", status = "ARCHIVED")

        // Alice: ACTIVE member of acme, SUSPENDED on beta, ACTIVE on archived tenant.
        seedMembership(tenant = acme, user = alice, role = "MEMBER", status = "ACTIVE")
        seedMembership(tenant = beta, user = alice, role = "ADMIN", status = "SUSPENDED")
        seedMembership(tenant = archived, user = alice, role = "OWNER", status = "ACTIVE")
        // Bob is a member of beta (should NOT appear in Alice's list).
        seedMembership(tenant = beta, user = bob, role = "MEMBER", status = "ACTIVE")

        val body = listTenants(subject = "alice")

        assertEquals(1, body.items.size, "only acme-health should appear for Alice")
        val only = body.items.single()
        assertEquals("acme-health", only.tenant.slug)
        assertEquals("Acme Health", only.tenant.displayName)
        assertEquals("ACTIVE", only.tenant.status)
        assertEquals("ACTIVE", only.status)
        assertEquals("MEMBER", only.role)
        assertEquals(alice.toString(), only.userId)
    }

    @Test
    fun `GET tenants list is sorted by slug ascending`() {
        val alice = provisionUser(subject = "alice")
        val zeta = seedTenant(slug = "zeta-clinic", displayName = "Zeta")
        val acme = seedTenant(slug = "acme-health", displayName = "Acme")
        val mid = seedTenant(slug = "mid-health", displayName = "Mid")
        seedMembership(zeta, alice)
        seedMembership(acme, alice)
        seedMembership(mid, alice)

        val body = listTenants("alice")

        assertEquals(
            listOf("acme-health", "mid-health", "zeta-clinic"),
            body.items.map { it.tenant.slug },
        )
    }

    @Test
    fun `GET tenants slug me returns membership for ACTIVE-on-ACTIVE`() {
        val alice = provisionUser(subject = "alice")
        val acme = seedTenant(slug = "acme-health", displayName = "Acme Health")
        seedMembership(tenant = acme, user = alice, role = "OWNER", status = "ACTIVE")

        val response = getMembership(subject = "alice", slug = "acme-health")
        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertEquals("acme-health", body.tenant.slug)
        assertEquals("OWNER", body.role)
        assertEquals("ACTIVE", body.status)
        assertEquals(alice.toString(), body.userId)
        UUID.fromString(body.membershipId)
        UUID.fromString(body.tenant.id)
    }

    @Test
    fun `GET tenants slug me returns 403 with tenancy-forbidden code when slug is unknown`() {
        provisionUser(subject = "alice")
        val response = getMembershipRaw(subject = "alice", slug = "does-not-exist")
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        val error = response.body!!
        assertEquals("tenancy.forbidden", error["code"])
        assertNotNull(error["message"])
    }

    @Test
    fun `GET tenants slug me returns 403 when caller is not a member`() {
        val alice = provisionUser(subject = "alice")
        val bob = provisionUser(subject = "bob")
        val acme = seedTenant(slug = "acme-health", displayName = "Acme")
        seedMembership(tenant = acme, user = bob, role = "MEMBER", status = "ACTIVE")

        // Acknowledge alice was provisioned so the compiler/linter doesn't
        // complain, and confirm identity row exists for cross-check.
        assertTrue(identityUserCount(alice) == 1)

        val response = getMembershipRaw(subject = "alice", slug = "acme-health")
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals("tenancy.forbidden", response.body!!["code"])
    }

    @Test
    fun `GET tenants slug me returns 403 when membership is SUSPENDED`() {
        val alice = provisionUser(subject = "alice")
        val acme = seedTenant(slug = "acme-health", displayName = "Acme")
        seedMembership(tenant = acme, user = alice, role = "MEMBER", status = "SUSPENDED")

        val response = getMembershipRaw(subject = "alice", slug = "acme-health")
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals("tenancy.forbidden", response.body!!["code"])
    }

    @Test
    fun `GET tenants slug me returns 403 when tenant status is SUSPENDED`() {
        val alice = provisionUser(subject = "alice")
        val suspended = seedTenant(
            slug = "paused-co",
            displayName = "Paused",
            status = "SUSPENDED",
        )
        seedMembership(tenant = suspended, user = alice, role = "OWNER", status = "ACTIVE")

        val response = getMembershipRaw(subject = "alice", slug = "paused-co")
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals("tenancy.forbidden", response.body!!["code"])
    }

    @Test
    fun `cross-user isolation — alice does not see bob's tenant via list`() {
        val alice = provisionUser(subject = "alice")
        val bob = provisionUser(subject = "bob")
        val acme = seedTenant(slug = "acme-health", displayName = "Acme")
        val beta = seedTenant(slug = "beta-clinic", displayName = "Beta")
        seedMembership(acme, alice)
        seedMembership(beta, bob)

        val aliceView = listTenants("alice").items.map { it.tenant.slug }.toSet()
        val bobView = listTenants("bob").items.map { it.tenant.slug }.toSet()

        assertEquals(setOf("acme-health"), aliceView)
        assertEquals(setOf("beta-clinic"), bobView)
    }

    @Test
    fun `cross-user isolation — alice cannot fetch bob's membership via slug me`() {
        val alice = provisionUser(subject = "alice")
        val bob = provisionUser(subject = "bob")
        val beta = seedTenant(slug = "beta-clinic", displayName = "Beta")
        seedMembership(tenant = beta, user = bob, role = "OWNER", status = "ACTIVE")
        assertTrue(identityUserCount(alice) == 1)

        val response = getMembershipRaw(subject = "alice", slug = "beta-clinic")
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals("tenancy.forbidden", response.body!!["code"])
    }

    @Test
    fun `X-Medcore-Tenant header is optional — request without it still succeeds`() {
        val alice = provisionUser(subject = "alice")
        val acme = seedTenant(slug = "acme-health", displayName = "Acme")
        seedMembership(acme, alice)

        val response = rest.exchange(
            "/api/v1/tenants",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            MembershipListProjection::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(1, response.body!!.items.size)
    }

    @Test
    fun `X-Medcore-Tenant header with unknown slug returns 403 via filter`() {
        provisionUser(subject = "alice")
        val response = rest.exchange(
            "/api/v1/tenants",
            HttpMethod.GET,
            HttpEntity<Void>(
                bearer(tokenFor("alice")).apply {
                    set("X-Medcore-Tenant", "no-such-tenant")
                },
            ),
            Map::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals("tenancy.forbidden", response.body!!["code"])
    }

    @Test
    fun `X-Medcore-Tenant header with valid active membership is accepted`() {
        val alice = provisionUser(subject = "alice")
        val acme = seedTenant(slug = "acme-health", displayName = "Acme")
        seedMembership(acme, alice)

        val response = rest.exchange(
            "/api/v1/tenants",
            HttpMethod.GET,
            HttpEntity<Void>(
                bearer(tokenFor("alice")).apply {
                    set("X-Medcore-Tenant", "acme-health")
                },
            ),
            MembershipListProjection::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(1, response.body!!.items.size)
    }

    @Test
    fun `me endpoint still works — no regression from 3A3`() {
        provisionUser(subject = "alice")
        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("alice", response.body!!["subject"])
    }

    // ---------------------------------------------------------------- helpers

    /** Triggers JIT provisioning via /me; returns the persisted identity.user.id. */
    private fun provisionUser(subject: String): UUID {
        val probe = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor(subject))),
            Map::class.java,
        )
        check(probe.statusCode == HttpStatus.OK) {
            "JIT provisioning call for subject=$subject did not return 200: ${probe.statusCode}"
        }
        return jdbc.queryForObject(
            "SELECT id FROM identity.\"user\" WHERE subject = ?",
            UUID::class.java,
            subject,
        ) ?: error("identity.user row not found for subject=$subject")
    }

    private fun seedTenant(
        slug: String,
        displayName: String,
        status: String = "ACTIVE",
    ): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        jdbc.update(
            """
            INSERT INTO tenancy.tenant
                (id, slug, display_name, status, created_at, updated_at, row_version)
            VALUES (?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
            id, slug, displayName, status, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
        )
        return id
    }

    private fun seedMembership(
        tenant: UUID,
        user: UUID,
        role: String = "MEMBER",
        status: String = "ACTIVE",
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

    private fun identityUserCount(userId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM identity.\"user\" WHERE id = ?",
            Int::class.java,
            userId,
        ) ?: 0

    private fun listTenants(subject: String): MembershipListProjection {
        val response = rest.exchange(
            "/api/v1/tenants",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor(subject))),
            MembershipListProjection::class.java,
        )
        check(response.statusCode == HttpStatus.OK) {
            "list returned ${response.statusCode} for subject=$subject"
        }
        return response.body!!
    }

    private fun getMembership(
        subject: String,
        slug: String,
    ) = rest.exchange(
        "/api/v1/tenants/$slug/me",
        HttpMethod.GET,
        HttpEntity<Void>(bearer(tokenFor(subject))),
        MembershipProjection::class.java,
    )

    @Suppress("UNCHECKED_CAST")
    private fun getMembershipRaw(
        subject: String,
        slug: String,
    ) = rest.exchange(
        "/api/v1/tenants/$slug/me",
        HttpMethod.GET,
        HttpEntity<Void>(bearer(tokenFor(subject))),
        Map::class.java,
    ) as org.springframework.http.ResponseEntity<Map<String, Any>>

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

    private fun bearer(token: String): HttpHeaders = HttpHeaders().apply {
        set(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    data class TenantProjection(
        val id: String = "",
        val slug: String = "",
        val displayName: String = "",
        val status: String = "",
    )

    data class MembershipProjection(
        val membershipId: String = "",
        val userId: String = "",
        val role: String = "",
        val status: String = "",
        val tenant: TenantProjection = TenantProjection(),
    )

    data class MembershipListProjection(
        val items: List<MembershipProjection> = emptyList(),
    )
}
