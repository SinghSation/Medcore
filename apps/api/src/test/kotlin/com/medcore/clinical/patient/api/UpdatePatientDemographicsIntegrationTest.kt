package com.medcore.clinical.patient.api

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
 * `PATCH /api/v1/tenants/{slug}/patients/{patientId}`
 * (Phase 4A.2).
 *
 * Covers:
 *   - Happy-path partial update (only a few fields change).
 *   - `If-Match` missing → 428.
 *   - `If-Match` stale → 409 `resource.conflict|reason=stale_row`.
 *   - Three-state partial semantics:
 *       * field absent — unchanged
 *       * field present null — cleared (only on nullable columns)
 *       * field present value — updated
 *   - Required-column Clear refused (422 `required`).
 *   - Cross-tenant id probe — 404.
 *   - MEMBER cannot PATCH — 403 + denial audit row.
 *   - `row_version` bump + audit emission with field-name list.
 *   - No-op PATCH (all values match existing) — no audit row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class UpdatePatientDemographicsIntegrationTest {

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
        jdbc.update("DELETE FROM clinical.patient_identifier")
        jdbc.update("DELETE FROM clinical.allergy")
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM clinical.patient_mrn_counter")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `OWNER updates nameMiddle and preferredLanguage — 200 with row_version bump + audit row`() {
        val (_, tenant, patientId) = seedOwnerAndPatient()

        val response = patch(
            "alice", "acme-health", patientId,
            ifMatch = "0",
            body = """{"nameMiddle":"Byron","preferredLanguage":"en"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val data = response.body!!["data"] as Map<*, *>
        assertThat(data["nameMiddle"]).isEqualTo("Byron")
        assertThat(data["preferredLanguage"]).isEqualTo("en")
        assertThat(data["rowVersion"])
            .describedAs("JPA @Version must bump after save")
            .isEqualTo(1)
        assertThat(response.headers.eTag).isEqualTo("\"1\"")

        val audit = auditRows(action = "clinical.patient.demographics_updated")
        assertThat(audit).hasSize(1)
        assertThat(audit.single()["reason"])
            .isEqualTo("intent:clinical.patient.update_demographics|fields:nameMiddle,preferredLanguage")
        assertThat(audit.single()["resource_id"]).isEqualTo(patientId.toString())
        assertThat(audit.single()["tenant_id"]).isEqualTo(tenant.toString())
    }

    @Test
    fun `PATCH without If-Match header returns 428`() {
        val (_, _, patientId) = seedOwnerAndPatient()
        val response = patch(
            "alice", "acme-health", patientId,
            ifMatch = null,
            body = """{"nameMiddle":"X"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.PRECONDITION_REQUIRED)
        assertThat(response.body!!["code"]).isEqualTo("request.precondition_required")
    }

    @Test
    fun `PATCH with stale If-Match returns 409 stale_row`() {
        val (_, _, patientId) = seedOwnerAndPatient()

        // Use an If-Match that doesn't match current row_version (which is 0).
        val response = patch(
            "alice", "acme-health", patientId,
            ifMatch = "42",
            body = """{"nameMiddle":"X"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!["code"]).isEqualTo("resource.conflict")
        @Suppress("UNCHECKED_CAST")
        val details = response.body!!["details"] as Map<String, Any>
        assertThat(details["reason"]).isEqualTo("stale_row")
    }

    @Test
    fun `PATCH with null on nullable column clears it`() {
        val (_, _, patientId) = seedOwnerAndPatient(nameMiddle = "Byron")
        // Initial row_version is 0.
        val response = patch(
            "alice", "acme-health", patientId,
            ifMatch = "0",
            body = """{"nameMiddle":null}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val data = response.body!!["data"] as Map<*, *>
        // @JsonInclude(NON_NULL) omits null fields from wire — so
        // absent = cleared.
        assertThat(data as Map<String, Any?>).doesNotContainKey("nameMiddle")
    }

    @Test
    fun `PATCH with null on required column returns 422 required`() {
        val (_, _, patientId) = seedOwnerAndPatient()
        val response = patch(
            "alice", "acme-health", patientId,
            ifMatch = "0",
            body = """{"nameGiven":null}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `PATCH with empty body returns 422 no_fields`() {
        val (_, _, patientId) = seedOwnerAndPatient()
        val response = patch(
            "alice", "acme-health", patientId,
            ifMatch = "0",
            body = "{}",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `no-op PATCH (sends values identical to existing) — 200, no row_version bump, no audit row`() {
        val (_, _, patientId) = seedOwnerAndPatient(nameGiven = "Ada")
        val response = patch(
            "alice", "acme-health", patientId,
            ifMatch = "0",
            body = """{"nameGiven":"Ada"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val data = response.body!!["data"] as Map<*, *>
        assertThat(data["rowVersion"])
            .describedAs("no-op PATCH must not bump row_version")
            .isEqualTo(0)

        val audit = auditRows(action = "clinical.patient.demographics_updated")
        assertThat(audit)
            .describedAs("no-op PATCH must NOT emit an audit row")
            .isEmpty()
    }

    @Test
    fun `cross-tenant patientId — 404`() {
        val (_, _, _) = seedOwnerAndPatient()
        val otherPatientId = UUID.randomUUID() // not in acme-health
        val response = patch(
            "alice", "acme-health", otherPatientId,
            ifMatch = "0",
            body = """{"nameMiddle":"X"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `MEMBER cannot PATCH — 403 + denial audit row`() {
        // Owner creates patient; then swap caller role to MEMBER.
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "MEMBER") // alice is MEMBER
        // seed patient directly via admin path since alice can't create
        val bobId = provisionUser("bob")
        seedMembership(tenant, bobId, role = "OWNER")
        val createResp = postAsOwner("bob", "acme-health",
            """{"nameGiven":"Ada","nameFamily":"Lovelace","birthDate":"1960-05-15","administrativeSex":"female"}""",
        )
        assertThat(createResp.statusCode).isEqualTo(HttpStatus.CREATED)
        val patientId = UUID.fromString((createResp.body!!["data"] as Map<*, *>)["id"] as String)

        val response = patch(
            "alice", "acme-health", patientId,
            ifMatch = "0",
            body = """{"nameMiddle":"X"}""",
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val denial = auditRows(action = "authz.write.denied")
        val patchDenial = denial.filter {
            (it["reason"] as String).startsWith("intent:clinical.patient.update_demographics")
        }
        assertThat(patchDenial).hasSize(1)
        assertThat(patchDenial.single()["reason"])
            .isEqualTo("intent:clinical.patient.update_demographics|denial:insufficient_authority")
    }

    // ---- helpers ----

    private data class Seed(val userId: UUID, val tenantId: UUID, val patientId: UUID)

    private fun seedOwnerAndPatient(
        nameGiven: String = "Ada",
        nameFamily: String = "Lovelace",
        nameMiddle: String? = null,
    ): Seed {
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")
        val body = buildString {
            append("{")
            append("\"nameGiven\":\"$nameGiven\",")
            append("\"nameFamily\":\"$nameFamily\",")
            if (nameMiddle != null) append("\"nameMiddle\":\"$nameMiddle\",")
            append("\"birthDate\":\"1960-05-15\",")
            append("\"administrativeSex\":\"female\"")
            append("}")
        }
        val created = postAsOwner("alice", "acme-health", body)
        check(created.statusCode == HttpStatus.CREATED) {
            "seed create failed: ${created.statusCode} ${created.body}"
        }
        val patientId = UUID.fromString(
            (created.body!!["data"] as Map<*, *>)["id"] as String,
        )
        return Seed(owner, tenant, patientId)
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

    private fun auditRows(action: String): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT action, reason, outcome, resource_type, resource_id, tenant_id::text
              FROM audit.audit_event
             WHERE action = ?
             ORDER BY recorded_at
            """.trimIndent(),
            action,
        )

    @Suppress("UNCHECKED_CAST")
    private fun postAsOwner(
        subject: String,
        slug: String,
        body: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients",
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
        ifMatch: String?,
        body: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
            if (ifMatch != null) add("If-Match", ifMatch)
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients/$patientId",
            HttpMethod.PATCH,
            HttpEntity(body, headers),
            Map::class.java,
        ) as ResponseEntity<Map<String, Any>>
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
