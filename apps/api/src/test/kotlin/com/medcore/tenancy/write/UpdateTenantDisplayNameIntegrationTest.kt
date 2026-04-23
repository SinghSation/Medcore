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
 * End-to-end integration coverage for `PATCH /api/v1/tenants/{slug}`
 * (Phase 3J.2). Proves the full WriteGate pipeline:
 *
 *   Controller → `@Valid` → `UpdateTenantDisplayNameValidator` →
 *   `UpdateTenantDisplayNamePolicy` (via WriteGate) →
 *   `UpdateTenantDisplayNameHandler` inside the gate transaction →
 *   RLS backstop → `UpdateTenantDisplayNameAuditor.onSuccess` in
 *   the same transaction → HTTP response.
 *
 * Every denial + validation + success path is exercised. The audit
 * log is asserted for the success + AUTHZ_WRITE_DENIED paths to
 * prove denial-reason granularity (Phase 3J.2 pressure-test
 * adjustment #1). No-op suppression is asserted by running the same
 * PATCH twice and counting audit rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class UpdateTenantDisplayNameIntegrationTest {

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

    // --- 401 unauthenticated ---

    @Test
    fun `PATCH without bearer token returns 401`() {
        val response = rest.exchange(
            "/api/v1/tenants/acme-health",
            HttpMethod.PATCH,
            HttpEntity("""{"displayName":"New"}""", jsonHeaders()),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // --- Success paths ---

    @Test
    fun `OWNER updates displayName — 200, DB row updated, audit row with intent slug`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant(slug = "acme-health", displayName = "Old")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = patch(
            subject = "alice",
            slug = "acme-health",
            body = """{"displayName":"Acme Health, P.C."}""",
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val data = response.body!!["data"] as Map<*, *>
        assertThat(data["slug"]).isEqualTo("acme-health")
        assertThat(data["displayName"]).isEqualTo("Acme Health, P.C.")

        assertThat(currentDisplayName("acme-health")).isEqualTo("Acme Health, P.C.")

        val audit = successAuditRows("acme-health")
        assertThat(audit).hasSize(1)
        assertThat(audit.single()["action"]).isEqualTo("tenancy.tenant.updated")
        assertThat(audit.single()["reason"]).isEqualTo("intent:tenant.update_display_name")
        assertThat(audit.single()["outcome"]).isEqualTo("SUCCESS")
        assertThat(audit.single()["resource_type"]).isEqualTo("tenant")
        assertThat(audit.single()["resource_id"]).isEqualTo("acme-health")
    }

    @Test
    fun `ADMIN updates displayName — 200 (ADMIN holds TENANT_UPDATE)`() {
        val admin = provisionUser("alice")
        val tenant = seedTenant(slug = "acme-health", displayName = "Old")
        seedMembership(tenant, admin, role = "ADMIN", status = "ACTIVE")

        val response = patch("alice", "acme-health", """{"displayName":"Acme v2"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(currentDisplayName("acme-health")).isEqualTo("Acme v2")
    }

    // --- 403 paths: every denial reason surfaces a distinct audit code ---

    @Test
    fun `MEMBER cannot update — 403 + AUTHZ_WRITE_DENIED with denial=insufficient_authority`() {
        val member = provisionUser("alice")
        val tenant = seedTenant(slug = "acme-health", displayName = "Old")
        seedMembership(tenant, member, role = "MEMBER", status = "ACTIVE")

        val response = patch("alice", "acme-health", """{"displayName":"New"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body!!["code"]).isEqualTo("auth.forbidden")

        assertThat(currentDisplayName("acme-health")).isEqualTo("Old")

        val denial = denialAuditRows("acme-health")
        assertThat(denial).hasSize(1)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:tenant.update_display_name|denial:insufficient_authority")
    }

    @Test
    fun `non-member cannot update — 403 + denial=not_a_member`() {
        provisionUser("alice")
        seedTenant(slug = "acme-health", displayName = "Old")

        val response = patch("alice", "acme-health", """{"displayName":"New"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val denial = denialAuditRows("acme-health")
        assertThat(denial).hasSize(1)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:tenant.update_display_name|denial:not_a_member")
    }

    @Test
    fun `suspended membership — 403 + denial=not_a_member (RLS collapses MEMBERSHIP_SUSPENDED)`() {
        // See AuthorityResolver KDoc "Known limitation: MEMBERSHIP_SUSPENDED
        // collapses to NOT_A_MEMBER." V8's read policy hides the
        // tenant row from suspended members, so the resolver cannot
        // distinguish "suspended member" from "never a member."
        // Carry-forward to a V13+ SECURITY DEFINER resolution function.
        val alice = provisionUser("alice")
        val tenant = seedTenant(slug = "acme-health", displayName = "Old")
        seedMembership(tenant, alice, role = "OWNER", status = "SUSPENDED")

        val response = patch("alice", "acme-health", """{"displayName":"New"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val denial = denialAuditRows("acme-health")
        assertThat(denial).hasSize(1)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:tenant.update_display_name|denial:not_a_member")
    }

    @Test
    fun `active OWNER of suspended tenant — 403 + denial=tenant_suspended`() {
        val alice = provisionUser("alice")
        val tenant = seedTenant(slug = "paused-co", displayName = "Paused", status = "SUSPENDED")
        seedMembership(tenant, alice, role = "OWNER", status = "ACTIVE")

        val response = patch("alice", "paused-co", """{"displayName":"Renamed"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val denial = denialAuditRows("paused-co")
        assertThat(denial).hasSize(1)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:tenant.update_display_name|denial:tenant_suspended")
    }

    @Test
    fun `unknown slug — 403 + denial=not_a_member (enumeration protection)`() {
        provisionUser("alice")
        val response = patch("alice", "does-not-exist", """{"displayName":"X"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        // resource_id captures the probed slug — trace malicious
        // attempts against specific slugs even when no tenant row exists.
        val denial = denialAuditRows("does-not-exist")
        assertThat(denial).hasSize(1)
        assertThat(denial.single()["reason"])
            .isEqualTo("intent:tenant.update_display_name|denial:not_a_member")
        assertThat(denial.single()["resource_id"]).isEqualTo("does-not-exist")
    }

    // --- 422 validation paths ---

    @Test
    fun `empty displayName — 422 with field=displayName code=NotBlank (Bean Validation)`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant(slug = "acme-health", displayName = "Old")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = patch("alice", "acme-health", """{"displayName":""}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(response.body!!["code"]).isEqualTo("request.validation_failed")
        assertThat(currentDisplayName("acme-health")).isEqualTo("Old")
        assertThat(successAuditRows("acme-health")).isEmpty()
        assertThat(denialAuditRows("acme-health")).isEmpty()
    }

    @Test
    fun `whitespace-only displayName — 422 via validator (code=blank)`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant(slug = "acme-health", displayName = "Old")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = patch("alice", "acme-health", """{"displayName":"   "}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(response.body!!["code"]).isEqualTo("request.validation_failed")
        assertThat(currentDisplayName("acme-health")).isEqualTo("Old")
    }

    @Test
    fun `overlong displayName (201 chars) — 422 via Bean Validation (Size)`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant(slug = "acme-health", displayName = "Old")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = patch("alice", "acme-health", """{"displayName":"${"a".repeat(201)}"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(response.body!!["code"]).isEqualTo("request.validation_failed")
    }

    @Test
    fun `missing displayName field — 422 (nullable DTO + NotBlank)`() {
        // UpdateTenantRequest.displayName is typed `String?` so a
        // missing field arrives as null and @NotBlank trips. If the
        // field were typed `String`, Jackson would throw at
        // deserialization and hit Phase 3G's deferred 400 path.
        val owner = provisionUser("alice")
        val tenant = seedTenant(slug = "acme-health", displayName = "Old")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = patch("alice", "acme-health", """{}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(response.body!!["code"]).isEqualTo("request.validation_failed")
    }

    // --- No-op optimisation ---

    @Test
    fun `no-op update (same displayName) — 200, row_version unchanged, NO audit row`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant(slug = "acme-health", displayName = "Acme Health")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")
        val before = rowVersion("acme-health")

        val response = patch("alice", "acme-health", """{"displayName":"Acme Health"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        assertThat(currentDisplayName("acme-health")).isEqualTo("Acme Health")
        assertThat(rowVersion("acme-health")).isEqualTo(before)
        assertThat(successAuditRows("acme-health")).isEmpty()
    }

    @Test
    fun `second identical PATCH after real change — only one audit row, no-op on second`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant(slug = "acme-health", displayName = "Old")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        patch("alice", "acme-health", """{"displayName":"New"}""")
        patch("alice", "acme-health", """{"displayName":"New"}""") // idempotent retry

        assertThat(successAuditRows("acme-health")).hasSize(1)
    }

    // --- Request-id parity + idempotency-key passthrough ---

    @Test
    fun `requestId in body matches X-Request-Id response header`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant(slug = "acme-health", displayName = "Old")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val response = patch("alice", "acme-health", """{"displayName":"New"}""")
        val headerRequestId = response.headers.getFirst("X-Request-Id")
        val bodyRequestId = response.body!!["requestId"] as String?

        assertThat(headerRequestId).isNotBlank()
        assertThat(bodyRequestId).isEqualTo(headerRequestId)
    }

    @Test
    fun `Idempotency-Key header is accepted (shape-only, not deduped in 3J)`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant(slug = "acme-health", displayName = "Old")
        seedMembership(tenant, owner, role = "OWNER", status = "ACTIVE")

        val headers = HttpHeaders().apply {
            add(HttpHeaders.AUTHORIZATION, "Bearer ${tokenFor("alice")}")
            add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            add("Idempotency-Key", "client-retry-token-abc123")
        }
        val response = rest.exchange(
            "/api/v1/tenants/acme-health",
            HttpMethod.PATCH,
            HttpEntity("""{"displayName":"Renamed"}""", headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(currentDisplayName("acme-health")).isEqualTo("Renamed")
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
        role: String,
        status: String,
    ) {
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

    private fun currentDisplayName(slug: String): String =
        jdbc.queryForObject(
            "SELECT display_name FROM tenancy.tenant WHERE slug = ?",
            String::class.java,
            slug,
        )!!

    private fun rowVersion(slug: String): Long =
        jdbc.queryForObject(
            "SELECT row_version FROM tenancy.tenant WHERE slug = ?",
            Long::class.java,
            slug,
        )!!

    private fun successAuditRows(slug: String): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT action, reason, outcome, resource_type, resource_id
              FROM audit.audit_event
             WHERE action = 'tenancy.tenant.updated' AND resource_id = ?
             ORDER BY recorded_at
            """.trimIndent(),
            slug,
        )

    private fun denialAuditRows(slug: String): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT action, reason, outcome, resource_type, resource_id
              FROM audit.audit_event
             WHERE action = 'authz.write.denied' AND resource_id = ?
             ORDER BY recorded_at
            """.trimIndent(),
            slug,
        )

    @Suppress("UNCHECKED_CAST")
    private fun patch(
        subject: String,
        slug: String,
        body: String,
    ): ResponseEntity<Map<String, Any>> = rest.exchange(
        "/api/v1/tenants/$slug",
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
