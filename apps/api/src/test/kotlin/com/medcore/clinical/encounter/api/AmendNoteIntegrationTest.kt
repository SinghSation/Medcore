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
 * `POST /api/v1/tenants/{slug}/encounters/{encounterId}/notes/{noteId}/amend`
 * (Phase 4D.6).
 *
 * Proves the full-stack invariant that a SIGNED note can be
 * amended via a NEW note row referencing the original through
 * `amends_id`, with single-level + same-encounter rules enforced
 * at both the handler (clean 409s) AND the V23 trigger
 * (integrity backstop).
 *
 * 13 cases — happy path, RBAC, conflict surfaces, cross-tenant +
 * cross-encounter 404s, closed-encounter asymmetry, sibling
 * amendments, and direct DB-trigger refusals.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class AmendNoteIntegrationTest {

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

    // ========================================================================
    // 1 — OWNER amend happy path: 201 + amendsId set + status=DRAFT + audit
    // ========================================================================

    @Test
    fun `OWNER amends signed note — 201 DRAFT + amendsId + ONE amend audit`() {
        val (userId, encounterId) = seedEncounter("alice", role = "OWNER")
        val originalNoteId = createNote("alice", "acme-health", encounterId, "original")
        signNote("alice", "acme-health", encounterId, originalNoteId)
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = postAmend("alice", "acme-health", encounterId, originalNoteId, "amended")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CREATED)

        @Suppress("UNCHECKED_CAST")
        val data = resp.body!!["data"] as Map<String, Any>
        val amendmentId = UUID.fromString(data["id"] as String)
        assertThat(data["status"]).isEqualTo("DRAFT")
        assertThat(data["amendsId"]).isEqualTo(originalNoteId.toString())
        assertThat(data["body"]).isEqualTo("amended")
        assertThat(data["createdBy"]).isEqualTo(userId.toString())
        assertThat(amendmentId).isNotEqualTo(originalNoteId)

        // Original is byte-stable post-amend (no trigger refusal,
        // because nobody updated it; this also confirms we did not
        // accidentally mutate the original).
        val origRow = jdbc.queryForMap(
            "SELECT status, body FROM clinical.encounter_note WHERE id = ?",
            originalNoteId,
        )
        assertThat(origRow["status"]).isEqualTo("SIGNED")
        assertThat(origRow["body"]).isEqualTo("original")

        // Exactly one amend-audit row, normative shape.
        val audit = auditRows("clinical.encounter.note.amended").single()
        assertThat(audit["actor_id"]).isEqualTo(userId)
        assertThat(audit["resource_type"]).isEqualTo("clinical.encounter.note")
        assertThat(audit["resource_id"]).isEqualTo(amendmentId.toString())
        assertThat(audit["outcome"]).isEqualTo("SUCCESS")
        assertThat(audit["reason"] as String)
            .isEqualTo("intent:clinical.encounter.note.amend|originalNoteId:$originalNoteId")
    }

    // ========================================================================
    // 2 — Amendment can be signed via the existing 4D.5 endpoint
    // ========================================================================

    @Test
    fun `amendment can be signed via 4D-5 sign endpoint — 200 SIGNED + sign audit`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val originalNoteId = createNote("alice", "acme-health", encounterId, "n1")
        signNote("alice", "acme-health", encounterId, originalNoteId)

        val amendResp = postAmend("alice", "acme-health", encounterId, originalNoteId, "amended")
        @Suppress("UNCHECKED_CAST")
        val amendmentId = UUID.fromString(
            (amendResp.body!!["data"] as Map<String, Any>)["id"] as String,
        )

        // Now sign the amendment using the existing 4D.5 endpoint —
        // proves the amendment lives in the same draft→sign workflow
        // as a fresh note.
        val signResp = postSign("alice", "acme-health", encounterId, amendmentId)
        assertThat(signResp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val signed = signResp.body!!["data"] as Map<String, Any>
        assertThat(signed["status"]).isEqualTo("SIGNED")
        assertThat(signed["signedAt"]).isNotNull()
        assertThat(signed["amendsId"]).isEqualTo(originalNoteId.toString())

        assertThat(auditRows("clinical.encounter.note.amended")).hasSize(1)
        assertThat(auditRows("clinical.encounter.note.signed")).hasSize(2) // original + amendment
    }

    // ========================================================================
    // 3 — ADMIN can amend
    // ========================================================================

    @Test
    fun `ADMIN can amend signed note — 201`() {
        val owner = provisionUser("owner")
        val admin = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, admin, role = "ADMIN")
        val patientId = createPatient("owner", "acme-health")
        val encounterId = createEncounterHttp("owner", "acme-health", patientId)
        val noteId = createNote("owner", "acme-health", encounterId, "n1")
        signNote("owner", "acme-health", encounterId, noteId)

        val resp = postAmend("alice", "acme-health", encounterId, noteId, "ADMIN amend")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    // ========================================================================
    // 4 — MEMBER cannot amend
    // ========================================================================

    @Test
    fun `MEMBER cannot amend — 403 + AUTHZ_WRITE_DENIED with amend intent`() {
        val owner = provisionUser("owner")
        val member = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        seedMembership(tenant, member, role = "MEMBER")
        val patientId = createPatient("owner", "acme-health")
        val encounterId = createEncounterHttp("owner", "acme-health", patientId)
        val noteId = createNote("owner", "acme-health", encounterId, "n1")
        signNote("owner", "acme-health", encounterId, noteId)
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = postAmend("alice", "acme-health", encounterId, noteId, "denied")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        assertThat(auditRows("clinical.encounter.note.amended")).isEmpty()
        val denied = auditRows("authz.write.denied").single()
        assertThat(denied["reason"] as String)
            .contains("intent:clinical.encounter.note.amend")
            .contains("denial:")
    }

    // ========================================================================
    // 5 — Amending a DRAFT note returns 409 cannot_amend_unsigned_note
    // ========================================================================

    @Test
    fun `amend a DRAFT note — 409 cannot_amend_unsigned_note + no amend audit`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val draftNoteId = createNote("alice", "acme-health", encounterId, "still draft")
        jdbc.update("DELETE FROM audit.audit_event")

        val resp = postAmend("alice", "acme-health", encounterId, draftNoteId, "amend attempt")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(resp.body!!["code"]).isEqualTo("resource.conflict")
        @Suppress("UNCHECKED_CAST")
        val details = resp.body!!["details"] as Map<String, Any?>
        assertThat(details["reason"]).isEqualTo("cannot_amend_unsigned_note")

        assertThat(auditRows("clinical.encounter.note.amended")).isEmpty()
    }

    // ========================================================================
    // 6 — Single-level chain enforcement (handler layer): cannot amend an amendment
    // ========================================================================

    @Test
    fun `amend an amendment — 409 cannot_amend_an_amendment + no extra audit`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val originalNoteId = createNote("alice", "acme-health", encounterId, "original")
        signNote("alice", "acme-health", encounterId, originalNoteId)
        // First amendment: must be SIGNED to be a candidate for the
        // chain-attempt below.
        val amendmentResp = postAmend(
            "alice", "acme-health", encounterId, originalNoteId, "first amend",
        )
        @Suppress("UNCHECKED_CAST")
        val firstAmendmentId = UUID.fromString(
            (amendmentResp.body!!["data"] as Map<String, Any>)["id"] as String,
        )
        signNote("alice", "acme-health", encounterId, firstAmendmentId)
        jdbc.update("DELETE FROM audit.audit_event")

        // Now attempt to amend the AMENDMENT itself.
        val resp = postAmend(
            "alice", "acme-health", encounterId, firstAmendmentId, "chain attempt",
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(resp.body!!["code"]).isEqualTo("resource.conflict")
        @Suppress("UNCHECKED_CAST")
        val details = resp.body!!["details"] as Map<String, Any?>
        assertThat(details["reason"]).isEqualTo("cannot_amend_an_amendment")

        assertThat(auditRows("clinical.encounter.note.amended")).isEmpty()
    }

    // ========================================================================
    // 7 — Cross-encounter amend: 404, no leak
    // ========================================================================

    @Test
    fun `amend with mismatched encounter URL — 404, no audit`() {
        val (_, encounterId1) = seedEncounter("alice", role = "OWNER")
        val patientId = jdbc.queryForObject(
            "SELECT patient_id FROM clinical.encounter WHERE id = ?",
            UUID::class.java,
            encounterId1,
        )!!
        // Create + sign note on e1 while still IN_PROGRESS, then
        // cancel so we can open a sibling encounter on the same
        // patient (4C.4 invariant).
        val noteOnE1 = createNote("alice", "acme-health", encounterId1, "on-e1")
        signNote("alice", "acme-health", encounterId1, noteOnE1)
        cancelEncounter("alice", encounterId1, "OTHER")
        val encounterId2 = createEncounterHttp("alice", "acme-health", patientId)
        jdbc.update("DELETE FROM audit.audit_event")

        // Route to e2 with the noteId that belongs to e1.
        val resp = postAmend("alice", "acme-health", encounterId2, noteOnE1, "wrong path")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.encounter.note.amended")).isEmpty()
    }

    // ========================================================================
    // 8 — Cross-tenant amend: 404, no leak
    // ========================================================================

    @Test
    fun `cross-tenant amend — 404, no audit, no leak`() {
        val alice = provisionUser("alice")
        val tenantA = seedTenant("tenant-a")
        val tenantB = seedTenant("tenant-b")
        seedMembership(tenantA, alice, role = "OWNER")
        seedMembership(tenantB, alice, role = "OWNER")
        val patientInA = createPatient("alice", "tenant-a")
        val encounterInA = createEncounterHttp("alice", "tenant-a", patientInA)
        val noteInA = createNote("alice", "tenant-a", encounterInA, "A-note")
        signNote("alice", "tenant-a", encounterInA, noteInA)
        jdbc.update("DELETE FROM audit.audit_event")

        // Try to amend tenant-A's note via tenant-B's URL.
        val resp = postAmend("alice", "tenant-b", encounterInA, noteInA, "cross")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(auditRows("clinical.encounter.note.amended")).isEmpty()
    }

    // ========================================================================
    // 9 — Closed-encounter asymmetry: amend works on FINISHED encounters
    // ========================================================================

    @Test
    fun `amend on FINISHED encounter — 201 (closed encounter does NOT block amends)`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val originalNoteId = createNote("alice", "acme-health", encounterId, "n1")
        signNote("alice", "acme-health", encounterId, originalNoteId)
        // Encounter has at least one signed note; finish it.
        finishEncounter("alice", encounterId)
        // Sanity: encounter is FINISHED.
        val statusAfter = jdbc.queryForObject(
            "SELECT status FROM clinical.encounter WHERE id = ?",
            String::class.java, encounterId,
        )
        assertThat(statusAfter).isEqualTo("FINISHED")

        val resp = postAmend(
            "alice", "acme-health", encounterId, originalNoteId, "post-finish correction",
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CREATED)
        @Suppress("UNCHECKED_CAST")
        val data = resp.body!!["data"] as Map<String, Any>
        assertThat(data["amendsId"]).isEqualTo(originalNoteId.toString())
        assertThat(data["status"]).isEqualTo("DRAFT")

        // Encounter status unchanged — amending does NOT reopen.
        val statusFinal = jdbc.queryForObject(
            "SELECT status FROM clinical.encounter WHERE id = ?",
            String::class.java, encounterId,
        )
        assertThat(statusFinal).isEqualTo("FINISHED")
    }

    // ========================================================================
    // 10 — Closed-encounter asymmetry: amend works on CANCELLED encounters
    // ========================================================================

    @Test
    fun `amend on CANCELLED encounter — 201 (closed encounter does NOT block amends)`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val originalNoteId = createNote("alice", "acme-health", encounterId, "n1")
        signNote("alice", "acme-health", encounterId, originalNoteId)
        cancelEncounter("alice", encounterId, "NO_SHOW")

        val resp = postAmend(
            "alice", "acme-health", encounterId, originalNoteId, "post-cancel correction",
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.CREATED)

        val statusFinal = jdbc.queryForObject(
            "SELECT status FROM clinical.encounter WHERE id = ?",
            String::class.java, encounterId,
        )
        assertThat(statusFinal).isEqualTo("CANCELLED")
    }

    // ========================================================================
    // 14 — Cross-slice: amendment on FINISHED encounter can still be signed
    //                   (4D.6 carve-out from the 4C.5 closed-encounter rule)
    // ========================================================================

    @Test
    fun `amendment on FINISHED encounter — sign succeeds (200 SIGNED)`() {
        val (userId, encounterId) = seedEncounter("alice", role = "OWNER")
        val originalNoteId = createNote("alice", "acme-health", encounterId, "n1")
        signNote("alice", "acme-health", encounterId, originalNoteId)
        finishEncounter("alice", encounterId)
        // Sanity: encounter is closed.
        assertThat(
            jdbc.queryForObject(
                "SELECT status FROM clinical.encounter WHERE id = ?",
                String::class.java, encounterId,
            ),
        ).isEqualTo("FINISHED")

        // Amend on FINISHED — produces a DRAFT amendment.
        val amendResp = postAmend(
            "alice", "acme-health", encounterId, originalNoteId, "post-finish correction",
        )
        assertThat(amendResp.statusCode).isEqualTo(HttpStatus.CREATED)
        @Suppress("UNCHECKED_CAST")
        val amendmentId = UUID.fromString(
            (amendResp.body!!["data"] as Map<String, Any>)["id"] as String,
        )

        // Sign the amendment — must succeed despite encounter being
        // closed. This is the 4D.6 carve-out from 4C.5: amendments
        // are exempt from the closed-encounter signing rule.
        val signResp = postSign("alice", "acme-health", encounterId, amendmentId)
        assertThat(signResp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val signed = signResp.body!!["data"] as Map<String, Any>
        assertThat(signed["status"]).isEqualTo("SIGNED")
        assertThat(signed["signedBy"]).isEqualTo(userId.toString())
        assertThat(signed["amendsId"]).isEqualTo(originalNoteId.toString())

        // Encounter status unchanged — signing the amendment does
        // NOT reopen the encounter.
        assertThat(
            jdbc.queryForObject(
                "SELECT status FROM clinical.encounter WHERE id = ?",
                String::class.java, encounterId,
            ),
        ).isEqualTo("FINISHED")
    }

    // ========================================================================
    // 11 — Sibling amendments: multiple amendments on the same original
    // ========================================================================

    @Test
    fun `sibling amendments on same original — both 201, share amendsId, ordered by createdAt`() {
        val (_, encounterId) = seedEncounter("alice", role = "OWNER")
        val originalNoteId = createNote("alice", "acme-health", encounterId, "original")
        signNote("alice", "acme-health", encounterId, originalNoteId)

        val first = postAmend(
            "alice", "acme-health", encounterId, originalNoteId, "first sibling",
        )
        Thread.sleep(5)
        val second = postAmend(
            "alice", "acme-health", encounterId, originalNoteId, "second sibling",
        )
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(second.statusCode).isEqualTo(HttpStatus.CREATED)

        @Suppress("UNCHECKED_CAST")
        val firstId = UUID.fromString(
            (first.body!!["data"] as Map<String, Any>)["id"] as String,
        )
        @Suppress("UNCHECKED_CAST")
        val secondId = UUID.fromString(
            (second.body!!["data"] as Map<String, Any>)["id"] as String,
        )
        assertThat(firstId).isNotEqualTo(secondId)

        // Both rows share the same amends_id (the original) and
        // are ordered by createdAt.
        val rows = jdbc.queryForList(
            """
            SELECT id, amends_id, created_at
              FROM clinical.encounter_note
             WHERE amends_id = ?
             ORDER BY created_at ASC
            """.trimIndent(),
            originalNoteId,
        )
        assertThat(rows).hasSize(2)
        assertThat(rows[0]["id"]).isEqualTo(firstId)
        assertThat(rows[1]["id"]).isEqualTo(secondId)
        assertThat(rows[0]["amends_id"]).isEqualTo(originalNoteId)
        assertThat(rows[1]["amends_id"]).isEqualTo(originalNoteId)

        assertThat(auditRows("clinical.encounter.note.amended")).hasSize(2)
    }

    // ========================================================================
    // 12 — V23 trigger: direct INSERT pointing amends_id at an amendment
    //                   is refused at the DB layer
    // ========================================================================

    @Test
    fun `DB trigger refuses direct INSERT with amends_id pointing at an amendment`() {
        val (userId, encounterId) = seedEncounter("alice", role = "OWNER")
        val originalNoteId = createNote("alice", "acme-health", encounterId, "original")
        signNote("alice", "acme-health", encounterId, originalNoteId)
        val amendmentResp = postAmend(
            "alice", "acme-health", encounterId, originalNoteId, "first amend",
        )
        @Suppress("UNCHECKED_CAST")
        val amendmentId = UUID.fromString(
            (amendmentResp.body!!["data"] as Map<String, Any>)["id"] as String,
        )
        val tenantId = jdbc.queryForObject(
            "SELECT tenant_id FROM clinical.encounter WHERE id = ?",
            UUID::class.java, encounterId,
        )!!

        // Bypass the handler entirely — admin-DS direct INSERT
        // pointing amends_id at the amendment, not the original.
        // V23 trigger MUST refuse with the documented message.
        val thrown = catchThrowable {
            jdbc.update(
                """
                INSERT INTO clinical.encounter_note(
                    id, tenant_id, encounter_id, body, status,
                    amends_id, created_at, updated_at, created_by, updated_by, row_version
                ) VALUES (?, ?, ?, ?, 'DRAFT',
                          ?, now(), now(), ?, ?, 0)
                """.trimIndent(),
                UUID.randomUUID(), tenantId, encounterId, "chain via SQL",
                amendmentId, userId, userId,
            )
        }
        assertThat(thrown)
            .isNotNull
            .hasMessageContaining("cannot amend an amendment")
    }

    // ========================================================================
    // 13 — V23 trigger: direct INSERT with mismatched encounter_id refused
    // ========================================================================

    @Test
    fun `DB trigger refuses direct INSERT where encounter_id differs from original`() {
        val (userId, encounterId1) = seedEncounter("alice", role = "OWNER")
        val patientId = jdbc.queryForObject(
            "SELECT patient_id FROM clinical.encounter WHERE id = ?",
            UUID::class.java, encounterId1,
        )!!
        val originalNoteId = createNote("alice", "acme-health", encounterId1, "n1")
        signNote("alice", "acme-health", encounterId1, originalNoteId)
        cancelEncounter("alice", encounterId1, "OTHER")
        val encounterId2 = createEncounterHttp("alice", "acme-health", patientId)
        val tenantId = jdbc.queryForObject(
            "SELECT tenant_id FROM clinical.encounter WHERE id = ?",
            UUID::class.java, encounterId2,
        )!!

        // Direct INSERT: amends_id points at original (lives on
        // encounterId1), but NEW.encounter_id is encounterId2.
        // V23 trigger MUST refuse — same-encounter rule.
        val thrown = catchThrowable {
            jdbc.update(
                """
                INSERT INTO clinical.encounter_note(
                    id, tenant_id, encounter_id, body, status,
                    amends_id, created_at, updated_at, created_by, updated_by, row_version
                ) VALUES (?, ?, ?, ?, 'DRAFT',
                          ?, now(), now(), ?, ?, 0)
                """.trimIndent(),
                UUID.randomUUID(), tenantId, encounterId2, "cross-encounter via SQL",
                originalNoteId, userId, userId,
            )
        }
        assertThat(thrown)
            .isNotNull
            .hasMessageContaining("amendment must belong to the same encounter")
    }

    // ========================================================================
    // Helpers — same shape as SignEncounterNoteIntegrationTest
    // ========================================================================

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

    @Suppress("UNCHECKED_CAST")
    private fun postAmend(
        subject: String,
        slug: String,
        encounterId: UUID,
        noteId: UUID,
        body: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/encounters/$encounterId/notes/$noteId/amend",
            HttpMethod.POST,
            HttpEntity("""{"body":"$body"}""", headers),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
    }

    private fun finishEncounter(subject: String, encounterId: UUID) {
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

    private fun cancelEncounter(
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

    private fun authJsonHeaders(token: String) = HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $token")
        add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
    }

    private fun bearerOnly(token: String) = HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}
