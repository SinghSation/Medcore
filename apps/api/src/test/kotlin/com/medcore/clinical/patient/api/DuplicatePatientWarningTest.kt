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
 * Verifies [com.medcore.clinical.patient.service.DuplicatePatientDetector]
 * behaviour end-to-end via `POST /api/v1/tenants/{slug}/patients`
 * (Phase 4A.2).
 *
 * Scenarios:
 *   - Exact match — same DOB + family + given → 409 with
 *     `clinical.patient.duplicate_warning` and candidate list.
 *   - Phonetic match — same DOB + soundex-equal family → 409.
 *     (Proves V16's `public.soundex` relocation + the runtime
 *     query's `public.soundex(...)` qualification both work.)
 *   - Retry with `X-Confirm-Duplicate: true` → 201.
 *   - No match — 201 with no warning.
 *   - Candidate payload shape — `{patientId, mrn}` only, no
 *     name/DOB/demographics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class DuplicatePatientWarningTest {

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
    fun `exact-match duplicate returns 409 with candidate list`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")

        // Seed one existing patient.
        val first = post("alice", "acme-health", body("Ada", "Lovelace", "1960-05-15"))
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        val firstMrn = (first.body!!["data"] as Map<*, *>)["mrn"] as String

        // Attempt to create a duplicate (same DOB + family + given).
        val dup = post("alice", "acme-health", body("Ada", "Lovelace", "1960-05-15"))
        assertThat(dup.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(dup.body!!["code"]).isEqualTo("clinical.patient.duplicate_warning")

        @Suppress("UNCHECKED_CAST")
        val details = dup.body!!["details"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val candidates = details["candidates"] as List<Map<String, String>>
        assertThat(candidates).hasSize(1)
        assertThat(candidates.single()["mrn"]).isEqualTo(firstMrn)
        assertThat(candidates.single()).containsOnlyKeys("patientId", "mrn")
        // Minimal disclosure discipline — no name/DOB/demographics in candidate.
        assertThat(candidates.single().keys).doesNotContain(
            "nameGiven", "nameFamily", "birthDate", "administrativeSex",
        )
    }

    @Test
    fun `phonetic match via soundex returns 409`() {
        // soundex("Lovelace") = soundex("Lavlis") (both L-4-series).
        // Pick a deliberately close family-name variant.
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")

        val first = post("alice", "acme-health", body("Ada", "Lovelace", "1960-05-15"))
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)

        // Different given name + different case + slightly different
        // family spelling — exact match misses, phonetic hits.
        val phonetic = post("alice", "acme-health", body("Adelaide", "Lavlis", "1960-05-15"))
        assertThat(phonetic.statusCode)
            .describedAs("phonetic match via public.soundex should fire 409 on same DOB + soundex-equal family")
            .isEqualTo(HttpStatus.CONFLICT)
        assertThat(phonetic.body!!["code"]).isEqualTo("clinical.patient.duplicate_warning")
    }

    @Test
    fun `X-Confirm-Duplicate true bypasses detection and creates`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")

        // First patient.
        val first = post("alice", "acme-health", body("Ada", "Lovelace", "1960-05-15"))
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)

        // Retry with the bypass header — must succeed.
        val confirmed = post(
            "alice", "acme-health",
            body("Ada", "Lovelace", "1960-05-15"),
            extraHeaders = mapOf("X-Confirm-Duplicate" to "true"),
        )
        assertThat(confirmed.statusCode).isEqualTo(HttpStatus.CREATED)

        val patientCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM clinical.patient",
            Int::class.java,
        )
        assertThat(patientCount).isEqualTo(2)
    }

    @Test
    fun `no match returns 201 without warning`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")

        val first = post("alice", "acme-health", body("Ada", "Lovelace", "1960-05-15"))
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)

        // Completely unrelated patient — different DOB + family.
        val second = post("alice", "acme-health", body("Grace", "Hopper", "1906-12-09"))
        assertThat(second.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `duplicate check does not advance MRN counter — failed create leaves counter alone`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")

        post("alice", "acme-health", body("Ada", "Lovelace", "1960-05-15")) // consumes 000001

        val dup = post("alice", "acme-health", body("Ada", "Lovelace", "1960-05-15"))
        assertThat(dup.statusCode).isEqualTo(HttpStatus.CONFLICT)

        // The aborted tx rolls back — counter still at 1 (next would be 2).
        val counterNext = jdbc.queryForObject(
            "SELECT next_value FROM clinical.patient_mrn_counter WHERE tenant_id = ?",
            Long::class.java, tenant,
        )
        assertThat(counterNext)
            .describedAs("duplicate-warning rollback must NOT have advanced the counter")
            .isEqualTo(1L)

        // Now a successful, genuinely-different create gets 000002.
        val next = post("alice", "acme-health", body("Grace", "Hopper", "1906-12-09"))
        assertThat(next.statusCode).isEqualTo(HttpStatus.CREATED)
        val nextMrn = (next.body!!["data"] as Map<*, *>)["mrn"] as String
        assertThat(nextMrn).isEqualTo("000002")
    }

    // ---- helpers ----

    private fun body(given: String, family: String, birthDate: String) = """
        {"nameGiven":"$given","nameFamily":"$family",
         "birthDate":"$birthDate","administrativeSex":"female"}
    """.trimIndent()

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

    @Suppress("UNCHECKED_CAST")
    private fun post(
        subject: String,
        slug: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(tokenFor(subject)).apply {
            add("X-Medcore-Tenant", slug)
            extraHeaders.forEach { (k, v) -> add(k, v) }
        }
        return rest.exchange(
            "/api/v1/tenants/$slug/patients",
            HttpMethod.POST,
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
