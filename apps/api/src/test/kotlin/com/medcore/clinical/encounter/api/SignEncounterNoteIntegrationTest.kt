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
 * `POST /api/v1/tenants/{slug}/encounters/{encounterId}/notes/{noteId}/sign`
 * (Phase 4D.5).
 *
 * Proves:
 *   1. OWNER sign happy path — 200 + note `status=SIGNED` +
 *      `signed_at`/`signed_by` populated + ONE
 *      `CLINICAL_ENCOUNTER_NOTE_SIGNED` audit row.
 *   2. ADMIN sign — 200.
 *   3. MEMBER sign — 403 + `AUTHZ_WRITE_DENIED` with the sign
 *      intent in reason + no `CLINICAL_ENCOUNTER_NOTE_SIGNED`.
 *   4. Re-sign already-signed — 409 `resource.conflict` +
 *      `details.reason: note_already_signed` + no second
 *      `CLINICAL_ENCOUNTER_NOTE_SIGNED`.
 *   5. 404 on unknown noteId.
 *   6. 404 on cross-tenant noteId.
 *   7. 404 when note belongs to a different encounter (URL path
 *      mismatch).
 *   8. DB trigger refuses direct `UPDATE body` on a signed row,
 *      verifying immutability is enforced below the handler.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class SignEncounterNoteIntegrationTest {

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
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `OWNER signs draft note — 200 + SIGNED row + ONE note-signed audit`() {
        val (userId, encounterId) = seedEncounter("alice", role = "OWNER")
        val noteId = createNote("alice", "acme-health", encounterId, "chief complaint stable")
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = postSign("alice", "acme-health", encounterId, noteId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val data = resp.body!!["data"] as Map<String, Any>
        assertThat(data["status"]).isEqualTo("SIGNED")
        assertThat(data["signedAt"]).isNotNull()
        assertThat(data["signedBy"]).isEqualTo(userId.toString())

        // DB row reflects the transition.
        val row = jdbc.queryForMap(
            "SELECT status, signed_by FROM clinical.encounter_note WHERE id = ?",
            noteId,
        )
        assertThat(row["status"]).isEqualTo("SIGNED")
        assertThat(row["signed_by"]).isEqualTo(userId)

        val audit = auditRows("clinical.encounter.note.signed").single()
        assertThat(audit["reason"])
            .isEqualTo("intent:clinical.encounter.note.sign")
        assertThat(audit["resource_type"]).isEqualTo("clinical.encounter.note")
        assertThat(audit["resource_id"]).isEqualTo(noteId.toString())
        assertThat(audit["outcome"]).isEqualTo("SUCCESS")
    }

    @Test
    fun `ADMIN can sign — 200`() {
        val owner = provisionUser("owner")
        val admin = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, admin, role = "ADMIN")
        val patientId = createPatient("owner", "acme-health")
        val encounterId = createEncounterHttp("owner", "acme-health", patientId)
        val noteId = createNote("owner", "acme-health", encounterId, "n1")

        val resp = postSign("alice", "acme-health", encounterId, noteId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `MEMBER cannot sign — 403 + AUTHZ_WRITE_DENIED with sign intent`() {
        val owner = provisionUser("owner")
        val member = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, member, role = "MEMBER")
        val patientId = createPatient("owner", "acme-health")
        val encounterId = createEncounterHttp("owner", "acme-health", patientId)
        val noteId = createNote("owner", "acme-health", encounterId, "n1")
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = postSign("alice", "acme-health", encounterId, noteId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        assertThat(auditRows("clinical.encounter.note.signed")).isEmpty()
        val denied = auditRows("authz.write.denied").single()
        assertThat(denied["reason"] as String)
            .contains("intent:clinical.encounter.note.sign")
            .contains("denial:")
    }

    @Test
    fun `re-sign already-signed — 409 resource conflict, no second signed audit`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val noteId = createNote("alice", "acme-health", encounterId, "n1")
        val first = postSign("alice", "acme-health", encounterId, noteId)
        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(auditRows("clinical.encounter.note.signed")).hasSize(1)

        val second = postSign("alice", "acme-health", encounterId, noteId)
        assertThat(second.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(second.body!!["code"]).isEqualTo("resource.conflict")
        @Suppress("UNCHECKED_CAST")
        val details = second.body!!["details"] as Map<String, Any?>
        assertThat(details["reason"]).isEqualTo("note_already_signed")

        // Still exactly one signed-audit row — re-sign does not
        // emit a second success.
        assertThat(auditRows("clinical.encounter.note.signed")).hasSize(1)
    }

    @Test
    fun `unknown noteId — 404, no signed audit`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = postSign(
            "alice",
            "acme-health",
            encounterId,
            UUID.randomUUID(),
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.encounter.note.signed")).isEmpty()
    }

    @Test
    fun `cross-tenant noteId — 404, no audit, no leak`() {
        val alice = provisionUser("alice")
        val tenantA = seedTenant("tenant-a")
        val tenantB = seedTenant("tenant-b")
        seedMembership(tenantA, alice, role = "OWNER")
        seedMembership(tenantB, alice, role = "OWNER")
        val patientInA = createPatient("alice", "tenant-a")
        val encounterInA = createEncounterHttp("alice", "tenant-a", patientInA)
        val noteInA = createNote("alice", "tenant-a", encounterInA, "A-note")
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = postSign("alice", "tenant-b", encounterInA, noteInA)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.encounter.note.signed")).isEmpty()
    }

    @Test
    fun `note on different encounter under same tenant — 404, no audit`() {
        val (_, encounterId1) = seedEncounter("alice", role = "OWNER")
        val patientId = jdbc.queryForObject(
            "SELECT patient_id FROM clinical.encounter WHERE id = ?",
            UUID::class.java,
            encounterId1,
        )!!
        val encounterId2 = createEncounterHttp("alice", "acme-health", patientId)
        val noteOnE1 = createNote("alice", "acme-health", encounterId1, "on-e1")
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = postSign("alice", "acme-health", encounterId2, noteOnE1)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.encounter.note.signed")).isEmpty()
    }

    @Test
    fun `DB trigger refuses direct body UPDATE on signed row`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val noteId = createNote("alice", "acme-health", encounterId, "n1")
        assertThat(postSign("alice", "acme-health", encounterId, noteId).statusCode)
            .isEqualTo(HttpStatus.OK)

        // Direct UPDATE via the admin datasource bypasses the
        // WriteGate. The trigger must block it.
        val thrown = catchThrowable {
            jdbc.update(
                "UPDATE clinical.encounter_note SET body = 'tampered' WHERE id = ?",
                noteId,
            )
        }
        assertThat(thrown)
            .isNotNull
            .hasMessageContaining("immutable once signed")

        // Body is unchanged.
        val body = jdbc.queryForObject(
            "SELECT body FROM clinical.encounter_note WHERE id = ?",
            String::class.java,
            noteId,
        )
        assertThat(body).isEqualTo("n1")
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

    @Suppress("UNCHECKED_CAST")
    private fun postSign(
        subject: String,
        slug: String,
        encounterId: UUID,
        noteId: UUID,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/encounters/$encounterId/notes/$noteId/sign",
            HttpMethod.POST,
            HttpEntity<Void>(headers),
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
