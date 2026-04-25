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
 * `POST /api/v1/tenants/{slug}/encounters/{encounterId}/cancel`
 * (Phase 4C.5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class CancelEncounterIntegrationTest {

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
        jdbc.update("DELETE FROM clinical.allergy")
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `OWNER cancel with reason NO_SHOW — 200 + CANCELLED + audit with reason`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = postCancel("alice", "acme-health", encounterId, "NO_SHOW")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val data = resp.body!!["data"] as Map<String, Any>
        assertThat(data["status"]).isEqualTo("CANCELLED")
        assertThat(data["cancelledAt"]).isNotNull()
        assertThat(data["cancelReason"]).isEqualTo("NO_SHOW")

        val row = jdbc.queryForMap(
            """
            SELECT status, cancelled_at, cancel_reason, finished_at
              FROM clinical.encounter WHERE id = ?
            """.trimIndent(),
            encounterId,
        )
        assertThat(row["status"]).isEqualTo("CANCELLED")
        assertThat(row["cancelled_at"]).isNotNull()
        assertThat(row["cancel_reason"]).isEqualTo("NO_SHOW")
        assertThat(row["finished_at"]).isNull()

        val audit = auditRows("clinical.encounter.cancelled").single()
        assertThat(audit["reason"])
            .isEqualTo("intent:clinical.encounter.cancel|reason:NO_SHOW")
        assertThat(audit["resource_type"]).isEqualTo("clinical.encounter")
        assertThat(audit["resource_id"]).isEqualTo(encounterId.toString())
        assertThat(audit["outcome"]).isEqualTo("SUCCESS")
    }

    @Test
    fun `ADMIN can cancel — 200`() {
        val owner = provisionUser("owner")
        val admin = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, admin, role = "ADMIN")
        val patientId = createPatient("owner", "acme-health")
        val encounterId = createEncounterHttp("owner", "acme-health", patientId)

        val resp = postCancel("alice", "acme-health", encounterId, "PATIENT_DECLINED")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `MEMBER cannot cancel — 403 + AUTHZ_WRITE_DENIED with cancel intent`() {
        val owner = provisionUser("owner")
        val member = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, member, role = "MEMBER")
        val patientId = createPatient("owner", "acme-health")
        val encounterId = createEncounterHttp("owner", "acme-health", patientId)
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = postCancel("alice", "acme-health", encounterId, "NO_SHOW")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        assertThat(auditRows("clinical.encounter.cancelled")).isEmpty()
        val denied = auditRows("authz.write.denied").single()
        assertThat(denied["reason"] as String)
            .contains("intent:clinical.encounter.cancel")
            .contains("denial:")
    }

    @Test
    fun `missing cancelReason — 422`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")

        val resp = postCancelRaw("alice", "acme-health", encounterId, """{}""")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `unknown cancelReason — 422`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")

        val resp = postCancelRaw(
            "alice",
            "acme-health",
            encounterId,
            """{"cancelReason":"MADE_UP_REASON"}""",
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `cancel already-FINISHED — 409 encounter_already_closed`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val noteId = createNote("alice", "acme-health", encounterId, "n")
        signNote("alice", "acme-health", encounterId, noteId)
        postFinish("alice", "acme-health", encounterId)

        val resp = postCancel("alice", "acme-health", encounterId, "OTHER")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        @Suppress("UNCHECKED_CAST")
        val details = resp.body!!["details"] as Map<String, Any?>
        assertThat(details["reason"]).isEqualTo("encounter_already_closed")
    }

    @Test
    fun `double-cancel — 409 encounter_already_closed, no second cancelled audit`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val first = postCancel("alice", "acme-health", encounterId, "NO_SHOW")
        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(auditRows("clinical.encounter.cancelled")).hasSize(1)

        val second = postCancel("alice", "acme-health", encounterId, "OTHER")
        assertThat(second.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(auditRows("clinical.encounter.cancelled")).hasSize(1)
    }

    @Test
    fun `404 unknown and cross-tenant encounter — no cancelled audit`() {
        val (_, aliceEncounter) = seedEncounter("alice", role = "OWNER")

        // Unknown encounter id.
        val unknown = postCancel("alice", "acme-health", UUID.randomUUID(), "NO_SHOW")
        assertThat(unknown.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.encounter.cancelled")).isEmpty()

        // Cross-tenant probe: bob tries to cancel alice's encounter
        // from tenant-b. Reuses `aliceEncounter` — creating a fresh
        // patient with the same demographics would trip the
        // duplicate-patient detector added in 4A.2.
        val bob = provisionUser("bob")
        val tenantB = seedTenant("tenant-b")
        seedMembership(tenantB, bob, role = "OWNER")
        val cross = postCancel("bob", "tenant-b", aliceEncounter, "NO_SHOW")
        assertThat(cross.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.encounter.cancelled")).isEmpty()
    }

    // ---- helpers ----

    private fun seedEncounter(subject: String, role: String = "OWNER"): Pair<UUID, UUID> {
        val user = provisionUser(subject)
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, user, role = role)
        val patientId = createPatient(subject, "acme-health")
        val encounterId = createEncounterHttp(subject, "acme-health", patientId)
        return user to encounterId
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

    private fun createNote(
        subject: String,
        slug: String,
        encounterId: UUID,
        body: String,
    ): UUID {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        val resp = rest.exchange(
            "/api/v1/tenants/$slug/encounters/$encounterId/notes",
            HttpMethod.POST,
            HttpEntity("""{"body":"$body"}""", headers),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val data = resp.body!!["data"] as Map<String, Any>
        return UUID.fromString(data["id"] as String)
    }

    private fun signNote(
        subject: String,
        slug: String,
        encounterId: UUID,
        noteId: UUID,
    ) {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        rest.exchange(
            "/api/v1/tenants/$slug/encounters/$encounterId/notes/$noteId/sign",
            HttpMethod.POST,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
    }

    private fun postFinish(subject: String, slug: String, encounterId: UUID) {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        rest.exchange(
            "/api/v1/tenants/$slug/encounters/$encounterId/finish",
            HttpMethod.POST,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun postCancel(
        subject: String,
        slug: String,
        encounterId: UUID,
        reason: String,
    ): ResponseEntity<Map<String, Any>> =
        postCancelRaw(subject, slug, encounterId, """{"cancelReason":"$reason"}""")

    @Suppress("UNCHECKED_CAST")
    private fun postCancelRaw(
        subject: String,
        slug: String,
        encounterId: UUID,
        body: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/encounters/$encounterId/cancel",
            HttpMethod.POST,
            HttpEntity(body, headers),
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
}
