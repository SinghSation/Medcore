package com.medcore.audit

import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class AuditTenancyIntegrationTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var mockOAuth2Server: MockOAuth2Server

    @Autowired
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

    @Test
    fun `list emits tenancy membership list with count reason`() {
        val alice = provisionUser("alice")
        val acme = seedTenant("acme-health")
        seedMembership(acme, alice)
        clearAuditRowsBeforeAssertions()

        val response = rest.exchange(
            "/api/v1/tenants",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            String::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode)

        val rows = auditRowsFor(action = "tenancy.membership.list")
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("SUCCESS", row.outcome)
        assertEquals(alice.toString(), row.actorId)
        assertEquals("count=1", row.reason)
        assertNull(row.tenantId, "list is not tenant-scoped")
    }

    @Test
    fun `header-driven success emits tenancy context set`() {
        val alice = provisionUser("alice")
        val acme = seedTenant("acme-health")
        seedMembership(acme, alice)
        clearAuditRowsBeforeAssertions()

        val response = rest.exchange(
            "/api/v1/tenants",
            HttpMethod.GET,
            HttpEntity<Void>(
                bearer(tokenFor("alice")).apply {
                    set("X-Medcore-Tenant", "acme-health")
                },
            ),
            String::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode)

        val rows = auditRowsFor(action = "tenancy.context.set")
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("SUCCESS", row.outcome)
        assertEquals(alice.toString(), row.actorId)
        assertEquals(acme.toString(), row.tenantId)
        assertEquals("via_header", row.reason)
    }

    @Test
    fun `header-driven denial on unknown slug emits membership denied`() {
        provisionUser("alice")
        clearAuditRowsBeforeAssertions()

        val response = rest.exchange(
            "/api/v1/tenants",
            HttpMethod.GET,
            HttpEntity<Void>(
                bearer(tokenFor("alice")).apply {
                    set("X-Medcore-Tenant", "unknown-tenant")
                },
            ),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)

        val rows = auditRowsFor(action = "tenancy.membership.denied")
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("DENIED", row.outcome)
        assertEquals("header_slug_unknown_or_not_member", row.reason)
    }

    @Test
    fun `path-driven denial on slug me emits membership denied at service layer`() {
        val alice = provisionUser("alice")
        val bob = provisionUser("bob")
        val beta = seedTenant("beta-clinic")
        seedMembership(beta, bob)
        clearAuditRowsBeforeAssertions()

        val response = rest.exchange(
            "/api/v1/tenants/beta-clinic/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)

        val rows = auditRowsFor(action = "tenancy.membership.denied")
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("DENIED", row.outcome)
        assertEquals(alice.toString(), row.actorId)
        assertEquals(beta.toString(), row.tenantId)
        assertEquals("not_a_member", row.reason)
    }

    @Test
    fun `path-driven denial distinguishes tenant_inactive from membership_inactive`() {
        val alice = provisionUser("alice")
        val suspendedTenant = seedTenant("suspended-co", status = "SUSPENDED")
        seedMembership(suspendedTenant, alice)

        val suspendedMembershipTenant = seedTenant("paused-team")
        seedMembership(suspendedMembershipTenant, alice, status = "SUSPENDED")
        clearAuditRowsBeforeAssertions()

        val tenantInactive = rest.exchange(
            "/api/v1/tenants/suspended-co/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, tenantInactive.statusCode)

        val membershipInactive = rest.exchange(
            "/api/v1/tenants/paused-team/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, membershipInactive.statusCode)

        val rows = auditRowsFor(action = "tenancy.membership.denied")
        assertEquals(2, rows.size)
        val reasons = rows.map { it.reason }.toSet()
        assertEquals(
            setOf("tenant_inactive", "membership_inactive"),
            reasons,
            "each denial case gets a distinct coarse reason code",
        )
    }

    @Test
    fun `slug unknown emits membership denied with null tenant id`() {
        provisionUser("alice")
        clearAuditRowsBeforeAssertions()

        val response = rest.exchange(
            "/api/v1/tenants/totally-missing/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)

        val rows = auditRowsFor(action = "tenancy.membership.denied")
        assertEquals(1, rows.size)
        val row = rows.single()
        assertNull(row.tenantId)
        assertEquals("slug_unknown", row.reason)
    }

    @Test
    fun `tenant me success does not emit tenancy context set`() {
        val alice = provisionUser("alice")
        val acme = seedTenant("acme-health")
        seedMembership(acme, alice)
        clearAuditRowsBeforeAssertions()

        val response = rest.exchange(
            "/api/v1/tenants/acme-health/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            String::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode)

        assertEquals(
            0,
            auditRowsFor(action = "tenancy.context.set").size,
            "per-slug membership lookup does not SET a request context",
        )
    }

    @Test
    fun `audit rows never persist slug or tenant display name`() {
        val alice = provisionUser("alice")
        val acme = seedTenant("acme-health", displayName = "Acme Health")
        seedMembership(acme, alice)
        clearAuditRowsBeforeAssertions()

        rest.exchange(
            "/api/v1/tenants",
            HttpMethod.GET,
            HttpEntity<Void>(
                bearer(tokenFor("alice")).apply {
                    set("X-Medcore-Tenant", "acme-health")
                },
            ),
            String::class.java,
        )
        rest.exchange(
            "/api/v1/tenants/acme-health/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("alice"))),
            String::class.java,
        )

        val forbidden = listOf("acme-health", "Acme Health")
        val rows = jdbc.queryForList(
            """
            SELECT action, actor_display, resource_type, resource_id, reason
              FROM audit.audit_event
            """.trimIndent(),
        )
        rows.forEach { row ->
            forbidden.forEach { needle ->
                row.values.filterIsInstance<String>().forEach { value ->
                    assertTrue(
                        !value.contains(needle),
                        "audit row for ${row["action"]} leaked $needle: $value",
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Trigger provisioning through the normal /me path to ensure the same
     * JIT flow runs that production uses. Clear the resulting audit rows
     * (via [clearAuditRowsBeforeAssertions]) before the test asserts on
     * fresh rows.
     */
    private fun provisionUser(subject: String): UUID {
        val probe = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor(subject))),
            String::class.java,
        )
        check(probe.statusCode == HttpStatus.OK) { "failed to provision $subject" }
        return jdbc.queryForObject(
            "SELECT id FROM identity.\"user\" WHERE subject = ?",
            UUID::class.java,
            subject,
        ) ?: error("identity.user not found for subject=$subject")
    }

    private fun clearAuditRowsBeforeAssertions() {
        jdbc.update("DELETE FROM audit.audit_event")
    }

    private fun seedTenant(
        slug: String,
        displayName: String = "Display for $slug",
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
            id, slug, displayName, status,
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
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
                ),
            ),
        ).serialize()

    private fun bearer(token: String) = HttpHeaders().apply {
        set(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    private data class AuditRow(
        val action: String,
        val outcome: String,
        val actorType: String,
        val actorId: String?,
        val tenantId: String?,
        val resourceType: String?,
        val resourceId: String?,
        val reason: String?,
    )

    private fun auditRowsFor(action: String): List<AuditRow> =
        jdbc.query(
            """
            SELECT action, outcome, actor_type,
                   actor_id::text  AS actor_id,
                   tenant_id::text AS tenant_id,
                   resource_type, resource_id, reason
              FROM audit.audit_event
             WHERE action = ?
             ORDER BY recorded_at
            """.trimIndent(),
            { rs, _ ->
                AuditRow(
                    action = rs.getString("action"),
                    outcome = rs.getString("outcome"),
                    actorType = rs.getString("actor_type"),
                    actorId = rs.getString("actor_id"),
                    tenantId = rs.getString("tenant_id"),
                    resourceType = rs.getString("resource_type"),
                    resourceId = rs.getString("resource_id"),
                    reason = rs.getString("reason"),
                )
            },
            action,
        )

    // Suppress unused param warning for `assertNotNull` kept for future use.
    @Suppress("unused")
    private fun use(vararg any: Any?) = assertNotNull(any)
}
