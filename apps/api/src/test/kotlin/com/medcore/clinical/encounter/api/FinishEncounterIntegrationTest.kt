package com.medcore.clinical.encounter.api

import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
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
 * `POST /api/v1/tenants/{slug}/encounters/{encounterId}/finish`
 * (Phase 4C.5).
 *
 * Proves the FINISH-requires-signed-note invariant plus the
 * usual state-machine identity discipline (404, 403, 409).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class FinishEncounterIntegrationTest {

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
    fun `OWNER finishes with 1 signed note — 200 + FINISHED + ONE finished audit`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val noteId = createNote("alice", "acme-health", encounterId, "n")
        signNote("alice", "acme-health", encounterId, noteId)
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = postFinish("alice", "acme-health", encounterId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val data = resp.body!!["data"] as Map<String, Any>
        assertThat(data["status"]).isEqualTo("FINISHED")
        assertThat(data["finishedAt"]).isNotNull()

        val row = jdbc.queryForMap(
            "SELECT status, finished_at FROM clinical.encounter WHERE id = ?",
            encounterId,
        )
        assertThat(row["status"]).isEqualTo("FINISHED")
        assertThat(row["finished_at"]).isNotNull()

        val audit = auditRows("clinical.encounter.finished").single()
        assertThat(audit["reason"]).isEqualTo("intent:clinical.encounter.finish")
        assertThat(audit["resource_type"]).isEqualTo("clinical.encounter")
        assertThat(audit["resource_id"]).isEqualTo(encounterId.toString())
        assertThat(audit["outcome"]).isEqualTo("SUCCESS")
    }

    @Test
    fun `ADMIN can finish — 200`() {
        val owner = provisionUser("owner")
        val admin = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, admin, role = "ADMIN")
        val patientId = createPatient("owner", "acme-health")
        val encounterId = createEncounterHttp("owner", "acme-health", patientId)
        val noteId = createNote("owner", "acme-health", encounterId, "n")
        signNote("owner", "acme-health", encounterId, noteId)

        val resp = postFinish("alice", "acme-health", encounterId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `MEMBER cannot finish — 403 + AUTHZ_WRITE_DENIED with finish intent`() {
        val owner = provisionUser("owner")
        val member = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, member, role = "MEMBER")
        val patientId = createPatient("owner", "acme-health")
        val encounterId = createEncounterHttp("owner", "acme-health", patientId)
        val noteId = createNote("owner", "acme-health", encounterId, "n")
        signNote("owner", "acme-health", encounterId, noteId)
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = postFinish("alice", "acme-health", encounterId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        assertThat(auditRows("clinical.encounter.finished")).isEmpty()
        val denied = auditRows("authz.write.denied").single()
        assertThat(denied["reason"] as String)
            .contains("intent:clinical.encounter.finish")
            .contains("denial:")
    }

    @Test
    fun `finish with 0 notes — 409 encounter_has_no_signed_notes`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = postFinish("alice", "acme-health", encounterId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(resp.body!!["code"]).isEqualTo("resource.conflict")
        @Suppress("UNCHECKED_CAST")
        val details = resp.body!!["details"] as Map<String, Any?>
        assertThat(details["reason"]).isEqualTo("encounter_has_no_signed_notes")
        assertThat(auditRows("clinical.encounter.finished")).isEmpty()
    }

    @Test
    fun `finish with only DRAFT notes — 409 encounter_has_no_signed_notes`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        createNote("alice", "acme-health", encounterId, "draft-1")
        createNote("alice", "acme-health", encounterId, "draft-2")

        val resp = postFinish("alice", "acme-health", encounterId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        @Suppress("UNCHECKED_CAST")
        val details = resp.body!!["details"] as Map<String, Any?>
        assertThat(details["reason"]).isEqualTo("encounter_has_no_signed_notes")
    }

    @Test
    fun `finish with 2 signed plus 1 draft — 200 (signed count greater than or equal to 1 is sufficient)`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val n1 = createNote("alice", "acme-health", encounterId, "a")
        val n2 = createNote("alice", "acme-health", encounterId, "b")
        createNote("alice", "acme-health", encounterId, "c-draft")
        signNote("alice", "acme-health", encounterId, n1)
        signNote("alice", "acme-health", encounterId, n2)

        val resp = postFinish("alice", "acme-health", encounterId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `double-finish — 409 encounter_already_closed, no second finished audit`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val noteId = createNote("alice", "acme-health", encounterId, "n")
        signNote("alice", "acme-health", encounterId, noteId)
        val first = postFinish("alice", "acme-health", encounterId)
        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(auditRows("clinical.encounter.finished")).hasSize(1)

        val second = postFinish("alice", "acme-health", encounterId)
        assertThat(second.statusCode).isEqualTo(HttpStatus.CONFLICT)
        @Suppress("UNCHECKED_CAST")
        val details = second.body!!["details"] as Map<String, Any?>
        assertThat(details["reason"]).isEqualTo("encounter_already_closed")
        assertThat(auditRows("clinical.encounter.finished")).hasSize(1)
    }

    @Test
    fun `finish on CANCELLED encounter — 409 encounter_already_closed`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        postCancel("alice", "acme-health", encounterId, "NO_SHOW")

        val resp = postFinish("alice", "acme-health", encounterId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        @Suppress("UNCHECKED_CAST")
        val details = resp.body!!["details"] as Map<String, Any?>
        assertThat(details["reason"]).isEqualTo("encounter_already_closed")
    }

    @Test
    fun `unknown encounterId — 404, no audit`() {
        val (_, _) = seedEncounter("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = postFinish("alice", "acme-health", UUID.randomUUID())
        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.encounter.finished")).isEmpty()
    }

    @Test
    fun `cross-tenant encounterId — 404, no leak`() {
        val alice = provisionUser("alice")
        val tenantA = seedTenant("tenant-a")
        val tenantB = seedTenant("tenant-b")
        seedMembership(tenantA, alice, role = "OWNER")
        seedMembership(tenantB, alice, role = "OWNER")
        val patientInA = createPatient("alice", "tenant-a")
        val encounterInA = createEncounterHttp("alice", "tenant-a", patientInA)
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = postFinish("alice", "tenant-b", encounterInA)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.encounter.finished")).isEmpty()
    }

    @Test
    fun `DB trigger refuses direct UPDATE on FINISHED row`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val noteId = createNote("alice", "acme-health", encounterId, "n")
        signNote("alice", "acme-health", encounterId, noteId)
        assertThat(postFinish("alice", "acme-health", encounterId).statusCode)
            .isEqualTo(HttpStatus.OK)

        // Direct UPDATE via admin DS bypasses the WriteGate. The
        // V21 trigger refuses any UPDATE on a closed encounter.
        val thrown = catchThrowable {
            jdbc.update(
                "UPDATE clinical.encounter SET updated_at = now() WHERE id = ?",
                encounterId,
            )
        }
        assertThat(thrown)
            .isNotNull
            .hasMessageContaining("immutable once closed")
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

    @Suppress("UNCHECKED_CAST")
    private fun postFinish(
        subject: String,
        slug: String,
        encounterId: UUID,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/encounters/$encounterId/finish",
            HttpMethod.POST,
            HttpEntity<Void>(headers),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun postCancel(
        subject: String,
        slug: String,
        encounterId: UUID,
        reason: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/encounters/$encounterId/cancel",
            HttpMethod.POST,
            HttpEntity("""{"cancelReason":"$reason"}""", headers),
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
