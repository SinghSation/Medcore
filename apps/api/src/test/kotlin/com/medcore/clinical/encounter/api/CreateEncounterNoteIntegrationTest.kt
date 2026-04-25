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
 * `POST /api/v1/tenants/{slug}/encounters/{encounterId}/notes`
 * (Phase 4D.1, VS1 Chunk E). First clinical-documentation write.
 *
 * Proves:
 *   1. Happy path: 201 + body + ETag + ONE
 *      `CLINICAL_ENCOUNTER_NOTE_CREATED` audit row,
 *      reason = "intent:clinical.encounter.note.create".
 *   2. Append-only: two POSTs produce two rows with distinct ids.
 *   3. 422 on empty body (both "" and "   ").
 *   4. 422 on body > 20,000 chars.
 *   5. 403 when MEMBER (no NOTE_WRITE).
 *   6. 404 on unknown / cross-tenant encounter (no existence leak).
 *   7. Audit reason never contains the body text (PHI discipline).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class CreateEncounterNoteIntegrationTest {

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
        jdbc.update("DELETE FROM clinical.problem")
        jdbc.update("DELETE FROM clinical.allergy")
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `OWNER creates a note — 201 + body + ETag + ONE CLINICAL_ENCOUNTER_NOTE_CREATED audit`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val body = "SOAP-ish: chief complaint, resolves with rest."
        val resp = post(
            "alice",
            "acme-health",
            encounterId,
            """{"body":${jsonString(body)}}""",
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(resp.headers.eTag).isEqualTo("\"0\"")
        val data = resp.body!!["data"] as Map<*, *>
        assertThat(data["body"]).isEqualTo(body)
        assertThat(data["encounterId"]).isEqualTo(encounterId.toString())

        val audit = auditRows("clinical.encounter.note.created")
        assertThat(audit).hasSize(1)
        val row = audit.single()
        assertThat(row["reason"]).isEqualTo("intent:clinical.encounter.note.create")
        assertThat(row["resource_type"]).isEqualTo("clinical.encounter.note")
        assertThat(row["resource_id"]).isEqualTo(data["id"])
        assertThat(row["outcome"]).isEqualTo("SUCCESS")
    }

    @Test
    fun `append-only — two POSTs produce two distinct rows`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")

        val r1 = post("alice", "acme-health", encounterId, """{"body":"first note"}""")
        val r2 = post("alice", "acme-health", encounterId, """{"body":"second note"}""")

        assertThat(r1.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(r2.statusCode).isEqualTo(HttpStatus.CREATED)
        val id1 = (r1.body!!["data"] as Map<*, *>)["id"]
        val id2 = (r2.body!!["data"] as Map<*, *>)["id"]
        assertThat(id1).isNotEqualTo(id2)
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM clinical.encounter_note",
                Long::class.java,
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `ADMIN can create a note — 201`() {
        val owner = provisionUser("owner")
        val admin = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, admin, role = "ADMIN")
        val patientId = createPatient("owner", "acme-health")
        val encounterId = createEncounter("owner", "acme-health", patientId)

        val resp = post("alice", "acme-health", encounterId, """{"body":"admin note"}""")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `MEMBER cannot create a note — 403, no created audit, AUTHZ_WRITE_DENIED`() {
        val owner = provisionUser("owner")
        val member = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, member, role = "MEMBER")
        val patientId = createPatient("owner", "acme-health")
        val encounterId = createEncounter("owner", "acme-health", patientId)
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = post("alice", "acme-health", encounterId, """{"body":"sneaky"}""")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        assertThat(auditRows("clinical.encounter.note.created")).isEmpty()
        val denied = auditRows("authz.write.denied")
        assertThat(denied).hasSize(1)
        assertThat(denied.single()["reason"] as String)
            .contains("intent:clinical.encounter.note.create")
            .contains("denial:")
    }

    @Test
    fun `empty body — 422`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val resp = post("alice", "acme-health", encounterId, """{"body":""}""")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `whitespace-only body — 422`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val resp = post("alice", "acme-health", encounterId, """{"body":"   \n  \t "}""")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `body longer than 20000 chars — 422`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val huge = "x".repeat(20_001)
        val resp = post("alice", "acme-health", encounterId, """{"body":"$huge"}""")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `unknown encounterId — 404, no note created, no created audit`() {
        val (_, _) = seedEncounter("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = post(
            "alice",
            "acme-health",
            UUID.randomUUID(),
            """{"body":"note"}""",
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM clinical.encounter_note",
                Long::class.java,
            ),
        ).isEqualTo(0)
        assertThat(auditRows("clinical.encounter.note.created")).isEmpty()
    }

    @Test
    fun `cross-tenant encounterId — 404 (no existence leak)`() {
        val alice = provisionUser("alice")
        val tenantA = seedTenant("tenant-a")
        val tenantB = seedTenant("tenant-b")
        seedMembership(tenantA, alice, role = "OWNER")
        seedMembership(tenantB, alice, role = "OWNER")
        val patientInA = createPatient("alice", "tenant-a")
        val encounterInA = createEncounter("alice", "tenant-a", patientInA)

        val resp = post("alice", "tenant-b", encounterInA, """{"body":"probe"}""")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `audit reason does NOT contain the note body (PHI discipline)`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val distinctive = "Distinct-Phrase-${UUID.randomUUID()}"
        val resp = post("alice", "acme-health", encounterId, """{"body":"$distinctive"}""")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CREATED)

        val row = auditRows("clinical.encounter.note.created").single()
        assertThat(row["reason"] as String).doesNotContain(distinctive)
    }

    @Test
    fun `note create on FINISHED encounter — 409 encounter_closed (Phase 4C-5)`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val noteId = createNoteForSetup("alice", encounterId, "n")
        signNoteForSetup("alice", encounterId, noteId)
        finishEncounterForSetup("alice", encounterId)
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = post("alice", "acme-health", encounterId, """{"body":"late entry"}""")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        @Suppress("UNCHECKED_CAST")
        val details = resp.body!!["details"] as Map<String, Any?>
        assertThat(details["reason"]).isEqualTo("encounter_closed")
        assertThat(auditRows("clinical.encounter.note.created")).isEmpty()
    }

    @Test
    fun `note create on CANCELLED encounter — 409 encounter_closed (Phase 4C-5)`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        cancelEncounterForSetup("alice", encounterId, "NO_SHOW")
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = post("alice", "acme-health", encounterId, """{"body":"late entry"}""")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        @Suppress("UNCHECKED_CAST")
        val details = resp.body!!["details"] as Map<String, Any?>
        assertThat(details["reason"]).isEqualTo("encounter_closed")
        assertThat(auditRows("clinical.encounter.note.created")).isEmpty()
    }

    // ---- helpers ----

    private fun seedEncounter(subject: String, role: String = "OWNER"): Pair<UUID, UUID> {
        val user = provisionUser(subject)
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, user, role = role)
        val patientId = createPatient(subject, "acme-health")
        val encounterId = createEncounter(subject, "acme-health", patientId)
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

    private fun createEncounter(subject: String, slug: String, patientId: UUID): UUID {
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

    @Suppress("UNCHECKED_CAST")
    private fun post(
        subject: String,
        slug: String,
        encounterId: UUID,
        body: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/encounters/$encounterId/notes",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    // Setup-only helpers used by the 4C.5 closed-encounter tests —
    // each wraps the corresponding HTTP endpoint and returns nothing
    // (or the minimum needed).
    private fun createNoteForSetup(
        subject: String,
        encounterId: UUID,
        body: String,
    ): UUID {
        val resp = post(subject, "acme-health", encounterId, """{"body":"$body"}""")
        @Suppress("UNCHECKED_CAST")
        val data = resp.body!!["data"] as Map<String, Any>
        return UUID.fromString(data["id"] as String)
    }

    private fun signNoteForSetup(
        subject: String,
        encounterId: UUID,
        noteId: UUID,
    ) {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", "acme-health")
        }
        rest.exchange(
            "/api/v1/tenants/acme-health/encounters/$encounterId/notes/$noteId/sign",
            HttpMethod.POST,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
    }

    private fun finishEncounterForSetup(
        subject: String,
        encounterId: UUID,
    ) {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", "acme-health")
        }
        rest.exchange(
            "/api/v1/tenants/acme-health/encounters/$encounterId/finish",
            HttpMethod.POST,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
    }

    private fun cancelEncounterForSetup(
        subject: String,
        encounterId: UUID,
        reason: String,
    ) {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", "acme-health")
        }
        rest.exchange(
            "/api/v1/tenants/acme-health/encounters/$encounterId/cancel",
            HttpMethod.POST,
            HttpEntity("""{"cancelReason":"$reason"}""", headers),
            Map::class.java,
        )
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

    /**
     * Conservative JSON string literal that escapes only the
     * characters actually present in our test inputs. Tests use
     * simple Latin strings — no need to depend on a JSON library.
     */
    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
