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
 * `POST /api/v1/tenants/{slug}/patients/{patientId}/encounters`
 * (Phase 4C.1, VS1 Chunk D). First clinical write that is not a
 * patient-demographic write.
 *
 * Proves:
 *   1. Happy path: 201 + body + ETag + exactly one
 *      `CLINICAL_ENCOUNTER_STARTED` audit row, correct reason slug.
 *   2. 422 on missing / unknown encounter class.
 *   3. 403 when MEMBER (no ENCOUNTER_WRITE authority).
 *   4. 404 on unknown patient id.
 *   5. 404 on cross-tenant patient id (no existence leak).
 *   6. Audit-row NORMATIVE shape.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class StartEncounterIntegrationTest {

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
    fun `OWNER starts encounter — 201 + body + ETag + ONE CLINICAL_ENCOUNTER_STARTED audit row`() {
        val (_, patientId) = seedPatient("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = post("alice", "acme-health", patientId, """{"encounterClass":"AMB"}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.headers.eTag).isEqualTo("\"0\"")

        val data = response.body!!["data"] as Map<*, *>
        assertThat(data["patientId"]).isEqualTo(patientId.toString())
        assertThat(data["status"]).isEqualTo("IN_PROGRESS")
        assertThat(data["encounterClass"]).isEqualTo("AMB")
        assertThat(data["startedAt"]).isNotNull()
        assertThat(data["finishedAt"]).isNull()

        val audit = auditRows("clinical.encounter.started")
        assertThat(audit).hasSize(1)
        val row = audit.single()
        assertThat(row["reason"]).isEqualTo("intent:clinical.encounter.start|class:AMB")
        assertThat(row["resource_type"]).isEqualTo("clinical.encounter")
        assertThat(row["resource_id"]).isEqualTo(data["id"])
        assertThat(row["outcome"]).isEqualTo("SUCCESS")
    }

    @Test
    fun `ADMIN can start encounter — 201`() {
        val owner = provisionUser("owner")
        val admin = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, admin, role = "ADMIN")
        val patientId = createPatient("owner", "acme-health")

        val response = post("alice", "acme-health", patientId, """{"encounterClass":"AMB"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `MEMBER cannot start encounter — 403, no started audit row`() {
        val owner = provisionUser("owner")
        val member = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, member, role = "MEMBER")
        val patientId = createPatient("owner", "acme-health")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = post("alice", "acme-health", patientId, """{"encounterClass":"AMB"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        assertThat(auditRows("clinical.encounter.started")).isEmpty()
        // WriteAuthorization denial emits AUTHZ_WRITE_DENIED with
        // the encounter intent in the reason.
        val denied = auditRows("authz.write.denied")
        assertThat(denied).hasSize(1)
        assertThat(denied.single()["reason"] as String)
            .contains("intent:clinical.encounter.start")
            .contains("denial:")
    }

    @Test
    fun `missing encounterClass — 422`() {
        val (_, patientId) = seedPatient("alice", role = "OWNER")
        val response = post("alice", "acme-health", patientId, """{}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `unknown encounterClass — 422`() {
        val (_, patientId) = seedPatient("alice", role = "OWNER")
        val response = post("alice", "acme-health", patientId, """{"encounterClass":"EMER"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `unknown patientId — 404, no encounter row, no started audit`() {
        val (_, _) = seedPatient("alice", role = "OWNER")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = post(
            "alice",
            "acme-health",
            UUID.randomUUID(),
            """{"encounterClass":"AMB"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM clinical.encounter", Long::class.java),
        ).isEqualTo(0)
        assertThat(auditRows("clinical.encounter.started")).isEmpty()
    }

    @Test
    fun `cross-tenant patientId — 404 (no existence leak)`() {
        val alice = provisionUser("alice")
        val tenantA = seedTenant("tenant-a")
        val tenantB = seedTenant("tenant-b")
        seedMembership(tenantA, alice, role = "OWNER")
        seedMembership(tenantB, alice, role = "OWNER")
        val patientInA = createPatient("alice", "tenant-a")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = post(
            "alice",
            "tenant-b",
            patientInA,
            """{"encounterClass":"AMB"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.encounter.started")).isEmpty()
    }

    // ========================================================================
    // Phase 4C.4 — at most one IN_PROGRESS per (tenant, patient)
    // ========================================================================

    @Test
    fun `second Start while IN_PROGRESS exists — 409 with existingEncounterId`() {
        val (_, patientId) = seedPatient("alice", role = "OWNER")

        val first = post("alice", "acme-health", patientId, """{"encounterClass":"AMB"}""")
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        @Suppress("UNCHECKED_CAST")
        val firstData = first.body!!["data"] as Map<String, Any>
        val firstEncounterId = firstData["id"] as String
        val encounterCountBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM clinical.encounter", Long::class.java,
        )
        jdbc.update("DELETE FROM audit.audit_event")

        val second = post("alice", "acme-health", patientId, """{"encounterClass":"AMB"}""")
        assertThat(second.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(second.body!!["code"]).isEqualTo("resource.conflict")
        @Suppress("UNCHECKED_CAST")
        val details = second.body!!["details"] as Map<String, Any?>
        assertThat(details["reason"]).isEqualTo("encounter_in_progress_exists")
        assertThat(details["existingEncounterId"]).isEqualTo(firstEncounterId)

        // No second encounter row; no second CLINICAL_ENCOUNTER_STARTED audit.
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM clinical.encounter", Long::class.java),
        ).isEqualTo(encounterCountBefore)
        assertThat(auditRows("clinical.encounter.started")).isEmpty()
    }

    @Test
    fun `Start succeeds after FINISHED encounter (closed does not block)`() {
        val (_, patientId) = seedPatient("alice", role = "OWNER")

        val first = post("alice", "acme-health", patientId, """{"encounterClass":"AMB"}""")
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        @Suppress("UNCHECKED_CAST")
        val firstId = UUID.fromString(
            (first.body!!["data"] as Map<String, Any>)["id"] as String,
        )
        // Add + sign a note so FINISH precondition is satisfied.
        val noteId = createNoteHelper("alice", "acme-health", firstId, "note")
        signNoteHelper("alice", "acme-health", firstId, noteId)
        finishEncounterHelper("alice", "acme-health", firstId)

        val second = post("alice", "acme-health", patientId, """{"encounterClass":"AMB"}""")
        assertThat(second.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `Start succeeds after CANCELLED encounter (closed does not block)`() {
        val (_, patientId) = seedPatient("alice", role = "OWNER")

        val first = post("alice", "acme-health", patientId, """{"encounterClass":"AMB"}""")
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        @Suppress("UNCHECKED_CAST")
        val firstId = UUID.fromString(
            (first.body!!["data"] as Map<String, Any>)["id"] as String,
        )
        cancelEncounterHelper("alice", "acme-health", firstId, "NO_SHOW")

        val second = post("alice", "acme-health", patientId, """{"encounterClass":"AMB"}""")
        assertThat(second.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `IN_PROGRESS on one patient does NOT block Start on a different patient in same tenant`() {
        val user = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, user, role = "OWNER")
        val patientA = createPatient("alice", "acme-health")
        // A second patient must be demographically distinct to
        // dodge the 4A.2 DuplicatePatientDetector.
        val patientB = createPatientWith(
            "alice", "acme-health",
            nameGiven = "Grace", nameFamily = "Hopper",
            birthDate = "1906-12-09",
        )

        assertThat(
            post("alice", "acme-health", patientA, """{"encounterClass":"AMB"}""")
                .statusCode,
        ).isEqualTo(HttpStatus.CREATED)
        // Patient B's first Start must succeed — the IN_PROGRESS
        // on patient A is not its concern. Invariant is per-patient.
        assertThat(
            post("alice", "acme-health", patientB, """{"encounterClass":"AMB"}""")
                .statusCode,
        ).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `DB constraint refuses a second IN_PROGRESS inserted directly via admin DS`() {
        // Belt-and-braces: handler pre-check is bypassable via
        // direct SQL; V22's partial unique index must still refuse
        // the second IN_PROGRESS row. Confirms the index is active
        // and correctly scoped (tenant_id, patient_id).
        val (_, patientId) = seedPatient("alice", role = "OWNER")
        val tenantId = jdbc.queryForObject(
            "SELECT id FROM tenancy.tenant WHERE slug = 'acme-health'",
            UUID::class.java,
        )!!
        val userId = jdbc.queryForObject(
            "SELECT id FROM identity.\"user\" WHERE subject = 'alice'",
            UUID::class.java,
        )!!

        // First IN_PROGRESS via direct INSERT (bypassing RLS via
        // admin DS is fine in this test — we own the connection).
        jdbc.update(
            """
            INSERT INTO clinical.encounter(
                id, tenant_id, patient_id, status, encounter_class,
                started_at, finished_at,
                created_at, updated_at, created_by, updated_by, row_version
            ) VALUES (?, ?, ?, 'IN_PROGRESS', 'AMB',
                now(), NULL, now(), now(), ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(), tenantId, patientId, userId, userId,
        )

        val thrown = org.assertj.core.api.Assertions.catchThrowable {
            jdbc.update(
                """
                INSERT INTO clinical.encounter(
                    id, tenant_id, patient_id, status, encounter_class,
                    started_at, finished_at,
                    created_at, updated_at, created_by, updated_by, row_version
                ) VALUES (?, ?, ?, 'IN_PROGRESS', 'AMB',
                    now(), NULL, now(), now(), ?, ?, 0)
                """.trimIndent(),
                UUID.randomUUID(), tenantId, patientId, userId, userId,
            )
        }
        assertThat(thrown)
            .isNotNull
            .hasMessageContaining("uq_clinical_encounter_one_in_progress_per_patient")
    }

    // ---- helpers ----

    private fun seedPatient(subject: String, role: String = "OWNER"): Pair<UUID, UUID> {
        val user = provisionUser(subject)
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, user, role = role)
        val patientId = createPatient(subject, "acme-health")
        return user to patientId
    }

    private fun createPatient(subject: String, slug: String): UUID =
        createPatientWith(
            subject, slug,
            nameGiven = "Ada",
            nameFamily = "Lovelace",
            birthDate = "1960-05-15",
        )

    private fun createPatientWith(
        subject: String,
        slug: String,
        nameGiven: String,
        nameFamily: String,
        birthDate: String,
        administrativeSex: String = "female",
    ): UUID {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        val body =
            """{"nameGiven":"$nameGiven","nameFamily":"$nameFamily","birthDate":"$birthDate","administrativeSex":"$administrativeSex"}"""
        val resp = rest.exchange(
            "/api/v1/tenants/$slug/patients",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val data = resp.body!!["data"] as Map<String, Any>
        return UUID.fromString(data["id"] as String)
    }

    private fun createNoteHelper(
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

    private fun signNoteHelper(
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

    private fun finishEncounterHelper(
        subject: String,
        slug: String,
        encounterId: UUID,
    ) {
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

    private fun cancelEncounterHelper(
        subject: String,
        slug: String,
        encounterId: UUID,
        reason: String,
    ) {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        rest.exchange(
            "/api/v1/tenants/$slug/encounters/$encounterId/cancel",
            HttpMethod.POST,
            HttpEntity("""{"cancelReason":"$reason"}""", headers),
            Map::class.java,
        )
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
            "/api/v1/tenants/$slug/patients/$patientId/encounters",
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
