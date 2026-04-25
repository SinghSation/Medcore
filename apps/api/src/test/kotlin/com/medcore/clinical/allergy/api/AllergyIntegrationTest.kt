package com.medcore.clinical.allergy.api

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
 * End-to-end coverage for the allergy HTTP surface (Phase 4E.1):
 *
 *   POST   /api/v1/tenants/{slug}/patients/{patientId}/allergies
 *   PATCH  /api/v1/tenants/{slug}/patients/{patientId}/allergies/{id}
 *   GET    /api/v1/tenants/{slug}/patients/{patientId}/allergies
 *
 * 16 cases covering: auth, RBAC, validation, cross-tenant /
 * cross-patient 404 discipline, status-transition audits
 * (UPDATED vs REVOKED dispatch), terminal-state refusal,
 * If-Match precondition, idempotent re-revoke suppression,
 * and list-audit shape including count=0.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class AllergyIntegrationTest {

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
        jdbc.update("DELETE FROM clinical.allergy")
        jdbc.update("DELETE FROM clinical.encounter_note")
        jdbc.update("DELETE FROM clinical.encounter")
        jdbc.update("DELETE FROM clinical.patient_mrn_counter")
        jdbc.update("DELETE FROM clinical.patient_identifier")
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    // ========================================================================
    // 1 — Auth
    // ========================================================================

    @Test
    fun `POST without bearer returns 401`() {
        val response = rest.exchange(
            "/api/v1/tenants/acme-health/patients/${UUID.randomUUID()}/allergies",
            HttpMethod.POST,
            HttpEntity(MINIMAL_ADD_BODY, jsonHeaders()),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ========================================================================
    // 2 — OWNER add happy path
    // ========================================================================

    @Test
    fun `OWNER adds allergy — 201 + ETag + audit row + DB row`() {
        val (userId, _, patientId) = seedOwnerAndPatient("alice")

        val response = post("alice", "acme-health", patientId, MINIMAL_ADD_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.headers.eTag).isEqualTo("\"0\"")

        @Suppress("UNCHECKED_CAST")
        val data = response.body!!["data"] as Map<String, Any>
        val allergyId = UUID.fromString(data["id"] as String)
        assertThat(data["substanceText"]).isEqualTo("Penicillin")
        assertThat(data["severity"]).isEqualTo("SEVERE")
        assertThat(data["status"]).isEqualTo("ACTIVE")
        assertThat(data["rowVersion"]).isEqualTo(0)
        assertThat(data["createdBy"]).isEqualTo(userId.toString())

        val rowCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM clinical.allergy WHERE id = ?",
            Long::class.java, allergyId,
        )
        assertThat(rowCount).isEqualTo(1L)

        val audit = auditRows("clinical.allergy.added").single()
        assertThat(audit["actor_id"]).isEqualTo(userId)
        assertThat(audit["resource_type"]).isEqualTo("clinical.allergy")
        assertThat(audit["resource_id"]).isEqualTo(allergyId.toString())
        assertThat(audit["outcome"]).isEqualTo("SUCCESS")
        assertThat(audit["reason"] as String)
            .isEqualTo("intent:clinical.allergy.add|severity:SEVERE")
    }

    // ========================================================================
    // 3 — ADMIN can add
    // ========================================================================

    @Test
    fun `ADMIN can add allergy — 201`() {
        val owner = provisionUser("owner")
        val admin = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, admin, role = "ADMIN")
        val patientId = createPatient("owner", "acme-health")

        val response = post("alice", "acme-health", patientId, MINIMAL_ADD_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    // ========================================================================
    // 4 — MEMBER cannot add (RBAC)
    // ========================================================================

    @Test
    fun `MEMBER cannot add allergy — 403 + AUTHZ_WRITE_DENIED with add intent`() {
        val owner = provisionUser("owner")
        val member = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, member, role = "MEMBER")
        val patientId = createPatient("owner", "acme-health")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = post("alice", "acme-health", patientId, MINIMAL_ADD_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        assertThat(auditRows("clinical.allergy.added")).isEmpty()
        val denied = auditRows("authz.write.denied").single()
        assertThat(denied["reason"] as String)
            .contains("intent:clinical.allergy.add")
            .contains("denial:")
    }

    // ========================================================================
    // 5 — Validation: blank substance
    // ========================================================================

    @Test
    fun `POST blank substanceText — 422`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val response = post(
            "alice", "acme-health", patientId,
            """{"substanceText":"   ","severity":"MILD"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    // ========================================================================
    // 6 — Validation: malformed severity enum
    // ========================================================================

    @Test
    fun `POST unknown severity token — 422 with format code`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val response = post(
            "alice", "acme-health", patientId,
            """{"substanceText":"Latex","severity":"PROBABLY_BAD"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    // ========================================================================
    // 7 — Cross-tenant patient on POST → 404 (no leak)
    // ========================================================================

    @Test
    fun `POST with cross-tenant patientId — 404, no audit, no leak`() {
        val alice = provisionUser("alice")
        val tenantA = seedTenant("tenant-a")
        val tenantB = seedTenant("tenant-b")
        seedMembership(tenantA, alice, role = "OWNER")
        seedMembership(tenantB, alice, role = "OWNER")
        val patientInA = createPatient("alice", "tenant-a")
        jdbc.update("DELETE FROM audit.audit_event")

        // Try to add allergy to tenant-A's patient via tenant-B's URL.
        val response = post("alice", "tenant-b", patientInA, MINIMAL_ADD_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.allergy.added")).isEmpty()
    }

    // ========================================================================
    // 8 — PATCH severity only (no status change) → UPDATED audit (no status_from/to)
    // ========================================================================

    @Test
    fun `PATCH severity only — 200 + UPDATED audit without status transition tokens`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (allergyId, _) = createAllergy("alice", patientId)
        jdbc.update("DELETE FROM audit.audit_event")

        val response = patch(
            "alice", "acme-health", patientId, allergyId,
            ifMatch = 0L,
            body = """{"severity":"MILD"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val audit = auditRows("clinical.allergy.updated").single()
        assertThat(audit["reason"] as String)
            .isEqualTo("intent:clinical.allergy.update|fields:severity")
    }

    // ========================================================================
    // 9 — PATCH ACTIVE→INACTIVE → UPDATED audit with status_from/status_to
    // ========================================================================

    @Test
    fun `PATCH ACTIVE to INACTIVE — 200 + UPDATED audit with status_from and status_to`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (allergyId, _) = createAllergy("alice", patientId)
        jdbc.update("DELETE FROM audit.audit_event")

        val response = patch(
            "alice", "acme-health", patientId, allergyId,
            ifMatch = 0L,
            body = """{"status":"INACTIVE"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val data = response.body!!["data"] as Map<String, Any>
        assertThat(data["status"]).isEqualTo("INACTIVE")

        val audit = auditRows("clinical.allergy.updated").single()
        assertThat(audit["reason"] as String).isEqualTo(
            "intent:clinical.allergy.update|fields:status|status_from:ACTIVE|status_to:INACTIVE",
        )
    }

    // ========================================================================
    // 10 — PATCH → ENTERED_IN_ERROR dispatches to REVOKED audit action
    // ========================================================================

    @Test
    fun `PATCH status to ENTERED_IN_ERROR — 200 + REVOKED audit + prior_status in reason`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (allergyId, _) = createAllergy("alice", patientId)
        jdbc.update("DELETE FROM audit.audit_event")

        val response = patch(
            "alice", "acme-health", patientId, allergyId,
            ifMatch = 0L,
            body = """{"status":"ENTERED_IN_ERROR"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val data = response.body!!["data"] as Map<String, Any>
        assertThat(data["status"]).isEqualTo("ENTERED_IN_ERROR")

        // Crucially: REVOKED action, NOT UPDATED.
        assertThat(auditRows("clinical.allergy.updated")).isEmpty()
        val audit = auditRows("clinical.allergy.revoked").single()
        assertThat(audit["resource_id"]).isEqualTo(allergyId.toString())
        assertThat(audit["reason"] as String)
            .isEqualTo("intent:clinical.allergy.revoke|prior_status:ACTIVE")
    }

    // ========================================================================
    // 11 — Terminal state refusal: PATCH on already-ENTERED_IN_ERROR with change
    // ========================================================================

    @Test
    fun `PATCH on terminal row with actual change — 409 allergy_terminal`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (allergyId, _) = createAllergy("alice", patientId)

        // Step 1: revoke.
        val revoked = patch(
            "alice", "acme-health", patientId, allergyId,
            ifMatch = 0L,
            body = """{"status":"ENTERED_IN_ERROR"}""",
        )
        assertThat(revoked.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val rev = revoked.body!!["data"] as Map<String, Any>
        val newRowVersion = (rev["rowVersion"] as Number).toLong()
        jdbc.update("DELETE FROM audit.audit_event")

        // Step 2: try to change severity on the terminal row.
        val response = patch(
            "alice", "acme-health", patientId, allergyId,
            ifMatch = newRowVersion,
            body = """{"severity":"MILD"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!["code"]).isEqualTo("resource.conflict")
        @Suppress("UNCHECKED_CAST")
        val details = response.body!!["details"] as Map<String, Any?>
        assertThat(details["reason"]).isEqualTo("allergy_terminal")
        assertThat(auditRows("clinical.allergy.updated")).isEmpty()
    }

    // ========================================================================
    // 12 — Idempotent re-revoke: terminal + no actual change → 200 + no audit
    // ========================================================================

    @Test
    fun `PATCH idempotent re-revoke on terminal row — 200 + no audit emission`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (allergyId, _) = createAllergy("alice", patientId)

        val revoked = patch(
            "alice", "acme-health", patientId, allergyId,
            ifMatch = 0L,
            body = """{"status":"ENTERED_IN_ERROR"}""",
        )
        @Suppress("UNCHECKED_CAST")
        val newRowVersion = ((revoked.body!!["data"] as Map<String, Any>)["rowVersion"] as Number)
            .toLong()
        jdbc.update("DELETE FROM audit.audit_event")

        // Re-send the same status — idempotent no-op.
        val response = patch(
            "alice", "acme-health", patientId, allergyId,
            ifMatch = newRowVersion,
            body = """{"status":"ENTERED_IN_ERROR"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        // NO audit emission — neither updated nor revoked.
        assertThat(auditRows("clinical.allergy.updated")).isEmpty()
        assertThat(auditRows("clinical.allergy.revoked")).isEmpty()
    }

    // ========================================================================
    // 13 — Stale If-Match → 409 stale_row
    // ========================================================================

    @Test
    fun `PATCH with stale If-Match — 409 stale_row`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (allergyId, _) = createAllergy("alice", patientId)
        // Mutate row to bump rowVersion to 1.
        patch(
            "alice", "acme-health", patientId, allergyId,
            ifMatch = 0L,
            body = """{"severity":"MILD"}""",
        )

        // Now try with stale rowVersion=0 again.
        val response = patch(
            "alice", "acme-health", patientId, allergyId,
            ifMatch = 0L,
            body = """{"severity":"MODERATE"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        @Suppress("UNCHECKED_CAST")
        val details = response.body!!["details"] as Map<String, Any?>
        assertThat(details["reason"]).isEqualTo("stale_row")
    }

    // ========================================================================
    // 14 — PATCH without If-Match → 428 PreconditionRequired
    // ========================================================================

    @Test
    fun `PATCH without If-Match header — 428 precondition required`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (allergyId, _) = createAllergy("alice", patientId)

        val headers = authJsonHeaders(tokenFor("alice")).apply {
            add("X-Medcore-Tenant", "acme-health")
            // deliberately no If-Match
        }
        val response = rest.exchange(
            "/api/v1/tenants/acme-health/patients/$patientId/allergies/$allergyId",
            HttpMethod.PATCH,
            HttpEntity("""{"severity":"MILD"}""", headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.PRECONDITION_REQUIRED)
    }

    // ========================================================================
    // 15 — Cross-tenant allergyId on PATCH → 404 (no existence leak)
    // ========================================================================

    @Test
    fun `PATCH cross-tenant allergyId — 404, no audit`() {
        val alice = provisionUser("alice")
        val tenantA = seedTenant("tenant-a")
        val tenantB = seedTenant("tenant-b")
        seedMembership(tenantA, alice, role = "OWNER")
        seedMembership(tenantB, alice, role = "OWNER")
        val patientInA = createPatient("alice", "tenant-a")
        val (allergyInA, _) = createAllergy("alice", patientInA, slug = "tenant-a")
        val patientInB = createPatient("alice", "tenant-b")
        jdbc.update("DELETE FROM audit.audit_event")

        // Route PATCH to tenant-B's URL with tenant-A's allergyId.
        val response = patch(
            "alice", "tenant-b", patientInB, allergyInA,
            ifMatch = 0L,
            body = """{"severity":"MILD"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.allergy.updated")).isEmpty()
        assertThat(auditRows("clinical.allergy.revoked")).isEmpty()
    }

    // ========================================================================
    // 16 — GET list ordering + count audit (covers populated AND empty paths)
    // ========================================================================

    @Test
    fun `GET list — ACTIVE first then INACTIVE then ENTERED_IN_ERROR + count audit`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")

        // Seed three allergies; transition one to INACTIVE and one to ENTERED_IN_ERROR.
        val (a1, _) = createAllergy("alice", patientId, body =
            """{"substanceText":"Tree nuts","severity":"MODERATE"}""")
        Thread.sleep(5)
        val (a2, _) = createAllergy("alice", patientId, body =
            """{"substanceText":"Latex","severity":"MILD"}""")
        Thread.sleep(5)
        val (a3, _) = createAllergy("alice", patientId, body =
            """{"substanceText":"Penicillin","severity":"SEVERE"}""")
        // a1 stays ACTIVE; a2 → INACTIVE; a3 → ENTERED_IN_ERROR.
        patch("alice", "acme-health", patientId, a2, ifMatch = 0L,
            body = """{"status":"INACTIVE"}""")
        patch("alice", "acme-health", patientId, a3, ifMatch = 0L,
            body = """{"status":"ENTERED_IN_ERROR"}""")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = getList("alice", "acme-health", patientId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val items = ((response.body!!["data"] as Map<String, Any>)["items"]) as List<Map<String, Any>>
        assertThat(items).hasSize(3)
        // ACTIVE first, then INACTIVE, then ENTERED_IN_ERROR.
        assertThat(items[0]["status"]).isEqualTo("ACTIVE")
        assertThat(items[0]["id"]).isEqualTo(a1.toString())
        assertThat(items[1]["status"]).isEqualTo("INACTIVE")
        assertThat(items[1]["id"]).isEqualTo(a2.toString())
        assertThat(items[2]["status"]).isEqualTo("ENTERED_IN_ERROR")
        assertThat(items[2]["id"]).isEqualTo(a3.toString())

        val audit = auditRows("clinical.allergy.list_accessed").single()
        assertThat(audit["reason"]).isEqualTo("intent:clinical.allergy.list|count:3")
        assertThat(audit["resource_type"]).isEqualTo("clinical.allergy")
        assertThat(audit["resource_id"]).isNull()

        // Also verify count=0 path on a different patient (zero-row
        // disclosure still emits an audit row).
        val secondPatientId = createPatientWith(
            "alice", "acme-health",
            nameGiven = "Grace", nameFamily = "Hopper", birthDate = "1906-12-09",
        )
        jdbc.update("DELETE FROM audit.audit_event")
        val emptyResponse = getList("alice", "acme-health", secondPatientId)
        assertThat(emptyResponse.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val emptyItems = ((emptyResponse.body!!["data"] as Map<String, Any>)["items"]) as List<*>
        assertThat(emptyItems).isEmpty()
        val emptyAudit = auditRows("clinical.allergy.list_accessed").single()
        assertThat(emptyAudit["reason"]).isEqualTo("intent:clinical.allergy.list|count:0")
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun seedOwnerAndPatient(subject: String): Triple<UUID, UUID, UUID> {
        val user = provisionUser(subject)
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, user, role = "OWNER")
        val patientId = createPatient(subject, "acme-health")
        return Triple(user, tenant, patientId)
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
            UUID::class.java, subject,
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

    private fun seedMembership(tenant: UUID, user: UUID, role: String) {
        val now = Instant.now()
        jdbc.update(
            """
            INSERT INTO tenancy.tenant_membership(
                id, tenant_id, user_id, role, status,
                created_at, updated_at, row_version
            ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(), tenant, user, role,
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
        )
    }

    private fun createPatient(subject: String, slug: String): UUID =
        createPatientWith(
            subject, slug,
            nameGiven = "Ada", nameFamily = "Lovelace", birthDate = "1960-05-15",
        )

    private fun createPatientWith(
        subject: String,
        slug: String,
        nameGiven: String,
        nameFamily: String,
        birthDate: String,
    ): UUID {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        val resp = rest.exchange(
            "/api/v1/tenants/$slug/patients",
            HttpMethod.POST,
            HttpEntity(
                """{"nameGiven":"$nameGiven","nameFamily":"$nameFamily","birthDate":"$birthDate","administrativeSex":"female"}""",
                headers,
            ),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val data = resp.body!!["data"] as Map<String, Any>
        return UUID.fromString(data["id"] as String)
    }

    private fun createAllergy(
        subject: String,
        patientId: UUID,
        slug: String = "acme-health",
        body: String = MINIMAL_ADD_BODY,
    ): Pair<UUID, Long> {
        val resp = post(subject, slug, patientId, body)
        check(resp.statusCode == HttpStatus.CREATED) { "createAllergy failed: ${resp.statusCode}" }
        @Suppress("UNCHECKED_CAST")
        val data = resp.body!!["data"] as Map<String, Any>
        return UUID.fromString(data["id"] as String) to (data["rowVersion"] as Number).toLong()
    }

    @Suppress("UNCHECKED_CAST")
    private fun post(
        subject: String,
        slug: String,
        patientId: UUID,
        body: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId/allergies",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun patch(
        subject: String,
        slug: String,
        patientId: UUID,
        allergyId: UUID,
        ifMatch: Long,
        body: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
            add("If-Match", "\"$ifMatch\"")
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId/allergies/$allergyId",
            HttpMethod.PATCH,
            HttpEntity(body, headers),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun getList(
        subject: String,
        slug: String,
        patientId: UUID,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId/allergies",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    private fun auditRows(action: String): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT action, actor_id, reason, outcome, resource_type, resource_id
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

    private companion object {
        const val MINIMAL_ADD_BODY: String =
            """{"substanceText":"Penicillin","severity":"SEVERE"}"""
    }
}
