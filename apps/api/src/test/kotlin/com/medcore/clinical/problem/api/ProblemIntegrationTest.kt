package com.medcore.clinical.problem.api

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
 * End-to-end coverage for the problem HTTP surface (Phase 4E.2):
 *
 *   POST   /api/v1/tenants/{slug}/patients/{patientId}/problems
 *   PATCH  /api/v1/tenants/{slug}/patients/{patientId}/problems/{id}
 *   GET    /api/v1/tenants/{slug}/patients/{patientId}/problems
 *
 * Cases cover: auth, RBAC, cross-tenant 404 discipline,
 * validation (incl. abatement-vs-onset coherence), all THREE
 * 409 conflict reasons (`stale_row`, `problem_terminal`,
 * `problem_invalid_transition` — the new RESOLVED ≠ INACTIVE
 * structural enforcement), three-way audit dispatch
 * (UPDATED / RESOLVED / REVOKED) including the recurrence
 * narrative (RESOLVED → ACTIVE), idempotent re-revoke
 * suppression, and list-audit shape including `count = 0`.
 *
 * Mirrors `AllergyIntegrationTest` shape; differences are
 * domain-specific (severity nullable, RESOLVED transitions,
 * `problem_invalid_transition` conflict reason).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class ProblemIntegrationTest {

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
        // V25 FK: clinical.problem → clinical.patient (default
        // RESTRICT). Delete BEFORE clinical.patient to keep the
        // cleanup chain valid. Same discipline as clinical.allergy.
        jdbc.update("DELETE FROM clinical.problem")
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
            "/api/v1/tenants/acme-health/patients/${UUID.randomUUID()}/problems",
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
    fun `OWNER adds problem — 201 + ETag + audit row + DB row`() {
        val (userId, _, patientId) = seedOwnerAndPatient("alice")

        val response = post("alice", "acme-health", patientId, MINIMAL_ADD_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.headers.eTag).isEqualTo("\"0\"")

        @Suppress("UNCHECKED_CAST")
        val data = response.body!!["data"] as Map<String, Any>
        val problemId = UUID.fromString(data["id"] as String)
        assertThat(data["conditionText"]).isEqualTo("Type 2 diabetes mellitus")
        assertThat(data["status"]).isEqualTo("ACTIVE")
        assertThat(data["rowVersion"]).isEqualTo(0)
        assertThat(data["createdBy"]).isEqualTo(userId.toString())
        // Severity NOT in MINIMAL_ADD_BODY — column null; @JsonInclude(NON_NULL)
        // omits it from the response.
        assertThat(data).doesNotContainKey("severity")

        val rowCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM clinical.problem WHERE id = ?",
            Long::class.java, problemId,
        )
        assertThat(rowCount).isEqualTo(1L)

        val audit = auditRows("clinical.problem.added").single()
        assertThat(audit["actor_id"]).isEqualTo(userId)
        assertThat(audit["resource_type"]).isEqualTo("clinical.problem")
        assertThat(audit["resource_id"]).isEqualTo(problemId.toString())
        assertThat(audit["outcome"]).isEqualTo("SUCCESS")
        // No severity token in the slug — see AddProblemAuditor KDoc.
        assertThat(audit["reason"] as String).isEqualTo("intent:clinical.problem.add")
    }

    // ========================================================================
    // 3 — ADMIN can add (mirrors allergy)
    // ========================================================================

    @Test
    fun `ADMIN adds problem — 201`() {
        val tenantId = seedTenant("acme-health")
        val adminUser = provisionUser("admin1")
        seedMembership(tenantId, adminUser, role = "ADMIN")

        val ownerUser = provisionUser("alice")
        seedMembership(tenantId, ownerUser, role = "OWNER")
        val patientId = createPatient("alice", "acme-health")

        val response = post("admin1", "acme-health", patientId, MINIMAL_ADD_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    // ========================================================================
    // 4 — MEMBER cannot add — 403 + AUTHZ_WRITE_DENIED audit
    // ========================================================================

    @Test
    fun `MEMBER cannot add — 403 + AUTHZ_WRITE_DENIED audit`() {
        val tenantId = seedTenant("acme-health")
        val ownerUser = provisionUser("alice")
        seedMembership(tenantId, ownerUser, role = "OWNER")
        val patientId = createPatient("alice", "acme-health")
        val memberUser = provisionUser("bob")
        seedMembership(tenantId, memberUser, role = "MEMBER")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = post("bob", "acme-health", patientId, MINIMAL_ADD_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val denials = auditRows("authz.write.denied")
        assertThat(denials).hasSize(1)
        assertThat(denials[0]["resource_type"]).isEqualTo("clinical.problem")
        assertThat(denials[0]["reason"] as String)
            .contains("intent:clinical.problem.add|denial:")
    }

    // ========================================================================
    // 5 — Non-member POST refused — 403 (NOT a 404 leak path)
    // ========================================================================
    //
    // This is the "alice is not a member of tenant B" path. The
    // policy gate runs before any DB lookup and refuses with 403
    // INSUFFICIENT_AUTHORITY (the AuthorityResolver returns
    // Denied). The status is deterministic at this layer.
    //
    // The "no existence leak" 404 invariant covers a DIFFERENT
    // path: alice IS a member of tenant B, but probes a patientId
    // that belongs to tenant A. There the handler's tenant-id
    // verification short-circuits and we return 404 indistinguishable
    // from "unknown patient". Constructing that scenario requires
    // alice to be a multi-tenant member, which the existing test
    // helpers don't model in 4E.2. Tracked as a follow-up cross-
    // tenant leak test alongside the future multi-tenant member
    // helper. For now this test locks down the 403 path
    // deterministically so the policy gate can't silently flip.

    @Test
    fun `non-member POST is refused with 403`() {
        // Tenant A with patient.
        val (_, _, aPatientId) = seedOwnerAndPatient("alice")

        // Tenant B with a different OWNER. Alice is NOT a member of B.
        val tenantB = seedTenant("rival-health")
        val carolUser = provisionUser("carol")
        seedMembership(tenantB, carolUser, role = "OWNER")

        // Alice tries to add a problem on her own patient via tenant B.
        val response = post("alice", "rival-health", aPatientId, MINIMAL_ADD_BODY)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    // ========================================================================
    // 6 — Validation: empty conditionText → 422
    // ========================================================================

    @Test
    fun `empty conditionText returns 422`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val response = post(
            "alice", "acme-health", patientId,
            body = """{"conditionText":""}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    // ========================================================================
    // 7 — Validation: abatementDate before onsetDate → 422
    // ========================================================================

    @Test
    fun `abatementDate before onsetDate returns 422`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val response = post(
            "alice", "acme-health", patientId,
            body = """{"conditionText":"Asthma","onsetDate":"2020-01-01","abatementDate":"2019-06-15"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        // The 422 envelope is uniform across bean-validation and
        // domain validators (Phase 3J.2): `details.validationErrors`
        // is a list of `{field, code}` objects.
        @Suppress("UNCHECKED_CAST")
        val details = response.body!!["details"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val errors = details["validationErrors"] as List<Map<String, Any>>
        assertThat(errors).anySatisfy { err ->
            assertThat(err["field"]).isEqualTo("abatementDate")
            assertThat(err["code"]).isEqualTo("before_onset_date")
        }
    }

    // ========================================================================
    // 8 — PATCH without If-Match → 428
    // ========================================================================

    @Test
    fun `PATCH without If-Match header — 428`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (problemId, _) = createProblem("alice", patientId)

        val headers = authJsonHeaders(tokenFor("alice")).apply {
            add("X-Medcore-Tenant", "acme-health")
        }
        val response = rest.exchange(
            "/api/v1/tenants/acme-health/patients/$patientId/problems/$problemId",
            HttpMethod.PATCH,
            HttpEntity("""{"status":"INACTIVE"}""", headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.PRECONDITION_REQUIRED)
    }

    // ========================================================================
    // 9 — PATCH with stale If-Match → 409 details.reason: stale_row
    // ========================================================================

    @Test
    fun `PATCH with stale If-Match — 409 stale_row`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (problemId, _) = createProblem("alice", patientId)
        // Bump rowVersion to 1 with a real change.
        patch(
            "alice", "acme-health", patientId, problemId,
            ifMatch = 0L, body = """{"severity":"MILD"}""",
        )

        // Try again with stale rowVersion=0.
        val response = patch(
            "alice", "acme-health", patientId, problemId,
            ifMatch = 0L, body = """{"severity":"SEVERE"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        @Suppress("UNCHECKED_CAST")
        val details = response.body!!["details"] as Map<String, Any>
        assertThat(details["reason"]).isEqualTo("stale_row")
    }

    // ========================================================================
    // 10 — PATCH ACTIVE → INACTIVE: emits CLINICAL_PROBLEM_UPDATED
    // ========================================================================

    @Test
    fun `PATCH ACTIVE to INACTIVE emits CLINICAL_PROBLEM_UPDATED with status_from_to`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (problemId, _) = createProblem("alice", patientId)

        val response = patch(
            "alice", "acme-health", patientId, problemId,
            ifMatch = 0L, body = """{"status":"INACTIVE"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val audit = auditRows("clinical.problem.updated").single()
        assertThat(audit["reason"] as String)
            .isEqualTo("intent:clinical.problem.update|fields:status|status_from:ACTIVE|status_to:INACTIVE")
        assertThat(auditRows("clinical.problem.resolved")).isEmpty()
        assertThat(auditRows("clinical.problem.revoked")).isEmpty()
    }

    // ========================================================================
    // 11 — PATCH ACTIVE → RESOLVED: dedicated CLINICAL_PROBLEM_RESOLVED audit
    // ========================================================================

    @Test
    fun `PATCH ACTIVE to RESOLVED emits CLINICAL_PROBLEM_RESOLVED with prior_status`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (problemId, _) = createProblem("alice", patientId)

        val response = patch(
            "alice", "acme-health", patientId, problemId,
            ifMatch = 0L, body = """{"status":"RESOLVED"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val audit = auditRows("clinical.problem.resolved").single()
        assertThat(audit["reason"] as String)
            .isEqualTo("intent:clinical.problem.resolve|prior_status:ACTIVE")
        // No UPDATED row — RESOLVED is its own action, NOT a flag
        // on UPDATED. Load-bearing distinction: RESOLVED ≠ INACTIVE.
        assertThat(auditRows("clinical.problem.updated")).isEmpty()
    }

    // ========================================================================
    // 12 — PATCH RESOLVED → ACTIVE recurrence — UPDATED, slug preserves narrative
    // ========================================================================

    @Test
    fun `PATCH RESOLVED to ACTIVE recurrence emits UPDATED with status_from_RESOLVED`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (problemId, _) = createProblem("alice", patientId)

        // ACTIVE → RESOLVED.
        patch(
            "alice", "acme-health", patientId, problemId,
            ifMatch = 0L, body = """{"status":"RESOLVED"}""",
        )
        jdbc.update("DELETE FROM audit.audit_event")

        // RESOLVED → ACTIVE (recurrence).
        val response = patch(
            "alice", "acme-health", patientId, problemId,
            ifMatch = 1L, body = """{"status":"ACTIVE"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        // Recurrence routes to UPDATED, not RESOLVED. The slug
        // preserves the recurrence narrative via status_from /
        // status_to so forensic queries can isolate it.
        val audit = auditRows("clinical.problem.updated").single()
        assertThat(audit["reason"] as String)
            .isEqualTo("intent:clinical.problem.update|fields:status|status_from:RESOLVED|status_to:ACTIVE")
        assertThat(auditRows("clinical.problem.resolved")).isEmpty()
    }

    // ========================================================================
    // 13 — PATCH RESOLVED → INACTIVE refused — 409 problem_invalid_transition
    // (THE LOAD-BEARING RESOLVED ≠ INACTIVE TEST)
    // ========================================================================

    @Test
    fun `PATCH RESOLVED to INACTIVE refused — 409 problem_invalid_transition`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (problemId, _) = createProblem("alice", patientId)
        patch(
            "alice", "acme-health", patientId, problemId,
            ifMatch = 0L, body = """{"status":"RESOLVED"}""",
        )
        jdbc.update("DELETE FROM audit.audit_event")

        val response = patch(
            "alice", "acme-health", patientId, problemId,
            ifMatch = 1L, body = """{"status":"INACTIVE"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        @Suppress("UNCHECKED_CAST")
        val details = response.body!!["details"] as Map<String, Any>
        assertThat(details["reason"]).isEqualTo("problem_invalid_transition")
        // No success audit row.
        assertThat(auditRows("clinical.problem.updated")).isEmpty()
        assertThat(auditRows("clinical.problem.resolved")).isEmpty()
    }

    // ========================================================================
    // 14 — PATCH ACTIVE → ENTERED_IN_ERROR — emits CLINICAL_PROBLEM_REVOKED
    // ========================================================================

    @Test
    fun `PATCH to ENTERED_IN_ERROR emits CLINICAL_PROBLEM_REVOKED with prior_status`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (problemId, _) = createProblem("alice", patientId)

        val response = patch(
            "alice", "acme-health", patientId, problemId,
            ifMatch = 0L, body = """{"status":"ENTERED_IN_ERROR"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val audit = auditRows("clinical.problem.revoked").single()
        assertThat(audit["reason"] as String)
            .isEqualTo("intent:clinical.problem.revoke|prior_status:ACTIVE")
        assertThat(auditRows("clinical.problem.updated")).isEmpty()
    }

    // ========================================================================
    // 15 — PATCH on terminal row with actual change — 409 problem_terminal
    // ========================================================================

    @Test
    fun `PATCH on terminal row with actual change — 409 problem_terminal`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (problemId, _) = createProblem("alice", patientId)
        patch(
            "alice", "acme-health", patientId, problemId,
            ifMatch = 0L, body = """{"status":"ENTERED_IN_ERROR"}""",
        )
        jdbc.update("DELETE FROM audit.audit_event")

        val response = patch(
            "alice", "acme-health", patientId, problemId,
            ifMatch = 1L, body = """{"status":"ACTIVE"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        @Suppress("UNCHECKED_CAST")
        val details = response.body!!["details"] as Map<String, Any>
        assertThat(details["reason"]).isEqualTo("problem_terminal")
        assertThat(auditRows("clinical.problem.updated")).isEmpty()
    }

    // ========================================================================
    // 16 — Idempotent re-revoke on terminal row — 200, no audit emitted
    // ========================================================================

    @Test
    fun `idempotent re-revoke on terminal row — 200 + no audit emission`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        val (problemId, _) = createProblem("alice", patientId)
        patch(
            "alice", "acme-health", patientId, problemId,
            ifMatch = 0L, body = """{"status":"ENTERED_IN_ERROR"}""",
        )
        jdbc.update("DELETE FROM audit.audit_event")

        // Re-revoke with same status — no actual change.
        val response = patch(
            "alice", "acme-health", patientId, problemId,
            ifMatch = 1L, body = """{"status":"ENTERED_IN_ERROR"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        // No-op: no audit row of any kind.
        assertThat(auditRows("clinical.problem.revoked")).isEmpty()
        assertThat(auditRows("clinical.problem.updated")).isEmpty()
    }

    // ========================================================================
    // 17 — GET list ordering + count audit (populated AND empty)
    // ========================================================================

    @Test
    fun `GET list — ACTIVE first then INACTIVE then RESOLVED then ENTERED_IN_ERROR + count audit`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")

        // Seed four problems and transition them so each status
        // bucket is populated. The list ordering invariant we
        // assert is status priority — created_at spacing is
        // not load-bearing for this test (4E.1 retro).
        val (p1, _) = createProblem("alice", patientId,
            body = """{"conditionText":"Asthma"}""")
        val (p2, _) = createProblem("alice", patientId,
            body = """{"conditionText":"Migraine"}""")
        val (p3, _) = createProblem("alice", patientId,
            body = """{"conditionText":"Bronchitis 2019"}""")
        val (p4, _) = createProblem("alice", patientId,
            body = """{"conditionText":"Wrong-entry"}""")
        // p1 stays ACTIVE; p2 → INACTIVE; p3 → RESOLVED; p4 → ENTERED_IN_ERROR.
        patch("alice", "acme-health", patientId, p2, ifMatch = 0L,
            body = """{"status":"INACTIVE"}""")
        patch("alice", "acme-health", patientId, p3, ifMatch = 0L,
            body = """{"status":"RESOLVED"}""")
        patch("alice", "acme-health", patientId, p4, ifMatch = 0L,
            body = """{"status":"ENTERED_IN_ERROR"}""")
        jdbc.update("DELETE FROM audit.audit_event")

        val response = getList("alice", "acme-health", patientId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val items = ((response.body!!["data"] as Map<String, Any>)["items"]) as List<Map<String, Any>>
        assertThat(items).hasSize(4)
        assertThat(items[0]["status"]).isEqualTo("ACTIVE")
        assertThat(items[0]["id"]).isEqualTo(p1.toString())
        assertThat(items[1]["status"]).isEqualTo("INACTIVE")
        assertThat(items[1]["id"]).isEqualTo(p2.toString())
        // RESOLVED ≠ INACTIVE — the bucket is its own slot
        // between INACTIVE and ENTERED_IN_ERROR.
        assertThat(items[2]["status"]).isEqualTo("RESOLVED")
        assertThat(items[2]["id"]).isEqualTo(p3.toString())
        assertThat(items[3]["status"]).isEqualTo("ENTERED_IN_ERROR")
        assertThat(items[3]["id"]).isEqualTo(p4.toString())

        val audit = auditRows("clinical.problem.list_accessed").single()
        // ADR-009 §2.7: per-page count + page_size + has_next.
        assertThat(audit["reason"])
            .isEqualTo(
                "intent:clinical.problem.list|count:4|page_size:50|has_next:false",
            )
        assertThat(audit["resource_type"]).isEqualTo("clinical.problem")
        assertThat(audit["resource_id"]).isNull()

        // count=0 path on a different patient.
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
        val emptyAudit = auditRows("clinical.problem.list_accessed").single()
        assertThat(emptyAudit["reason"])
            .isEqualTo(
                "intent:clinical.problem.list|count:0|page_size:50|has_next:false",
            )
    }

    // ========================================================================
    // Pagination (chunk E — ADR-009; full 4-bucket BucketedCursor walk)
    // ========================================================================

    @Test
    fun `pageSize=2 walks across all 4 status buckets correctly`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")
        // Seed 4 problems, transition each so all four status
        // buckets are populated exactly once. Bucket order:
        // ACTIVE (0), INACTIVE (1), RESOLVED (2),
        // ENTERED_IN_ERROR (3) — full ADR-009 §2.5 spectrum.
        val (p1, _) = createProblem("alice", patientId,
            body = """{"conditionText":"Asthma"}""")
        val (p2, _) = createProblem("alice", patientId,
            body = """{"conditionText":"Migraine"}""")
        val (p3, _) = createProblem("alice", patientId,
            body = """{"conditionText":"Bronchitis 2019"}""")
        val (p4, _) = createProblem("alice", patientId,
            body = """{"conditionText":"Wrong-entry"}""")
        // p1 stays ACTIVE; p2 → INACTIVE; p3 → RESOLVED; p4 → ENTERED_IN_ERROR.
        patch("alice", "acme-health", patientId, p2, ifMatch = 0L,
            body = """{"status":"INACTIVE"}""")
        patch("alice", "acme-health", patientId, p3, ifMatch = 0L,
            body = """{"status":"RESOLVED"}""")
        patch("alice", "acme-health", patientId, p4, ifMatch = 0L,
            body = """{"status":"ENTERED_IN_ERROR"}""")
        jdbc.update("DELETE FROM audit.audit_event")

        // Page 1: ACTIVE + INACTIVE (buckets 0, 1).
        val firstResp = getList("alice", "acme-health", patientId, pageSize = 2)
        assertThat(firstResp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val firstData = firstResp.body!!["data"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val firstItems = firstData["items"] as List<Map<String, Any>>
        assertThat(firstItems).hasSize(2)
        assertThat(firstItems[0]["id"]).isEqualTo(p1.toString())
        assertThat(firstItems[0]["status"]).isEqualTo("ACTIVE")
        assertThat(firstItems[1]["id"]).isEqualTo(p2.toString())
        assertThat(firstItems[1]["status"]).isEqualTo("INACTIVE")
        @Suppress("UNCHECKED_CAST")
        val firstPageInfo = firstData["pageInfo"] as Map<String, Any?>
        assertThat(firstPageInfo["hasNextPage"]).isEqualTo(true)
        val cursor1 = firstPageInfo["nextCursor"] as String
        assertThat(cursor1).isNotBlank
        assertThat(cursor1).doesNotContain("clinical")

        // Page 2: cursor walks INTO RESOLVED + ENTERED_IN_ERROR
        // (buckets 2, 3 — load-bearing RESOLVED ≠ INACTIVE
        // distinction preserved across the page boundary).
        val secondResp = getList(
            "alice", "acme-health", patientId,
            pageSize = 2, cursor = cursor1,
        )
        assertThat(secondResp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val secondData = secondResp.body!!["data"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val secondItems = secondData["items"] as List<Map<String, Any>>
        assertThat(secondItems).hasSize(2)
        assertThat(secondItems[0]["id"]).isEqualTo(p3.toString())
        assertThat(secondItems[0]["status"]).isEqualTo("RESOLVED")
        assertThat(secondItems[1]["id"]).isEqualTo(p4.toString())
        assertThat(secondItems[1]["status"]).isEqualTo("ENTERED_IN_ERROR")
        @Suppress("UNCHECKED_CAST")
        val secondPageInfo = secondData["pageInfo"] as Map<String, Any?>
        assertThat(secondPageInfo["hasNextPage"]).isEqualTo(false)
        assertThat(secondPageInfo["nextCursor"]).isNull()

        // Two audit rows, one per page-fetch.
        val rows = auditRows("clinical.problem.list_accessed")
        assertThat(rows).hasSize(2)
        assertThat(rows[0]["reason"])
            .isEqualTo(
                "intent:clinical.problem.list|count:2|page_size:2|has_next:true",
            )
        assertThat(rows[1]["reason"])
            .isEqualTo(
                "intent:clinical.problem.list|count:2|page_size:2|has_next:false",
            )
    }

    @Test
    fun `pageSize=0 returns 422 out_of_range`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")

        val resp = getList("alice", "acme-health", patientId, pageSize = 0)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        @Suppress("UNCHECKED_CAST")
        val errors = ((resp.body!!["details"] as Map<String, Any>)["validationErrors"])
            as List<Map<String, Any>>
        assertThat(errors).anySatisfy { err ->
            assertThat(err["field"]).isEqualTo("pageSize")
            assertThat(err["code"]).isEqualTo("out_of_range")
        }
    }

    @Test
    fun `malformed cursor returns 422 cursor|malformed`() {
        val (_, _, patientId) = seedOwnerAndPatient("alice")

        val resp = getList(
            "alice", "acme-health", patientId,
            pageSize = 50, cursor = "!!not-a-valid-cursor!!",
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        @Suppress("UNCHECKED_CAST")
        val errors = ((resp.body!!["details"] as Map<String, Any>)["validationErrors"])
            as List<Map<String, Any>>
        assertThat(errors).anySatisfy { err ->
            assertThat(err["field"]).isEqualTo("cursor")
            assertThat(err["code"]).isEqualTo("malformed")
        }
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

    private fun createProblem(
        subject: String,
        patientId: UUID,
        slug: String = "acme-health",
        body: String = MINIMAL_ADD_BODY,
    ): Pair<UUID, Long> {
        val resp = post(subject, slug, patientId, body)
        check(resp.statusCode == HttpStatus.CREATED) {
            "createProblem failed: ${resp.statusCode}"
        }
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
            "/api/v1/tenants/$slug/patients/$patientId/problems",
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
        problemId: UUID,
        ifMatch: Long,
        body: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
            add("If-Match", "\"$ifMatch\"")
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId/problems/$problemId",
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
        pageSize: Int? = null,
        cursor: String? = null,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        val query = buildList {
            if (pageSize != null) add("pageSize=$pageSize")
            if (cursor != null) add("cursor=${java.net.URLEncoder.encode(cursor, "UTF-8")}")
        }.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId/problems$query",
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
            """{"conditionText":"Type 2 diabetes mellitus"}"""
    }
}
