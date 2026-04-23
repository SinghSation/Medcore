package com.medcore.clinical.patient.api

import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
 * Proves [com.medcore.clinical.patient.mrn.MrnGenerator]'s
 * concurrency claim at the HTTP level (Phase 4A.2,
 * design-pack refinement #3: "at least 50 parallel creates").
 *
 * Fires [PARALLEL_CREATES] concurrent `POST /patients` requests
 * into the same tenant from the same owner principal. Asserts:
 *
 *   - All responses are 201.
 *   - All minted MRNs are distinct (proves no collision).
 *   - The MRN set is exactly `{000001, 000002, ..., 000050}`
 *     (proves monotonic contiguous sequence — no gaps, no
 *     duplicates).
 *   - The `clinical.patient_mrn_counter.next_value` matches
 *     [PARALLEL_CREATES] (proves no lost increments).
 *
 * Distinct from [MrnGeneratorTest], which exercises the
 * generator directly; this test exercises the whole HTTP
 * pipeline — controller, WriteGate, PhiRlsTxHook, handler,
 * generator — through the app's thread-pool. A bug anywhere in
 * the pipeline that breaks MRN uniqueness surfaces here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class PatientCreateConcurrencyTest {

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
        jdbc.update("DELETE FROM clinical.patient")
        jdbc.update("DELETE FROM clinical.patient_mrn_counter")
        jdbc.update("DELETE FROM tenancy.tenant_membership")
        jdbc.update("DELETE FROM tenancy.tenant")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `$PARALLEL_CREATES concurrent creates yield $PARALLEL_CREATES distinct MRNs with no gaps`() {
        val owner = provisionUser("alice")
        val tenant = seedTenant("acme-health")
        seedMembership(tenant, owner, role = "OWNER")

        val executor = Executors.newFixedThreadPool(PARALLEL_CREATES)
        val token = tokenFor("alice")
        val futures = (1..PARALLEL_CREATES).map { i ->
            CompletableFuture.supplyAsync({
                post(
                    token, "acme-health",
                    // Each request has a distinct name to avoid the
                    // duplicate-warning path — we're testing MRN
                    // uniqueness under concurrency, not dedup.
                    """{"nameGiven":"First$i","nameFamily":"Last$i","birthDate":"1990-01-01","administrativeSex":"female"}""",
                )
            }, executor)
        }
        val responses = futures.map { it.get(60, TimeUnit.SECONDS) }
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)

        // All succeed.
        responses.forEach { response ->
            assertThat(response.statusCode)
                .describedAs("every concurrent create must return 201")
                .isEqualTo(HttpStatus.CREATED)
        }

        // MRNs are distinct + exactly the contiguous range 1..N.
        val mrns = responses.map {
            (it.body!!["data"] as Map<*, *>)["mrn"] as String
        }.toSortedSet()
        val expected = (1..PARALLEL_CREATES).map { "%06d".format(it) }.toSortedSet()
        assertThat(mrns)
            .describedAs("MRN set must be exactly {000001..%06d} — no duplicates, no gaps".format(PARALLEL_CREATES))
            .isEqualTo(expected)

        // DB-side: counter sits at N, patient count is N.
        val counterNext = jdbc.queryForObject(
            "SELECT next_value FROM clinical.patient_mrn_counter WHERE tenant_id = ?",
            Long::class.java, tenant,
        )
        assertThat(counterNext)
            .describedAs("counter must equal number of successful creates (no lost increments)")
            .isEqualTo(PARALLEL_CREATES.toLong())
        val patientCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM clinical.patient WHERE tenant_id = ?",
            Int::class.java, tenant,
        )
        assertThat(patientCount).isEqualTo(PARALLEL_CREATES)
    }

    // ---- helpers ----

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
        token: String,
        slug: String,
        body: String,
    ): ResponseEntity<Map<String, Any>> {
        val headers = authJsonHeaders(token).apply {
            add("X-Medcore-Tenant", slug)
            // Bypass duplicate detection — this test is proving MRN
            // uniqueness under concurrency, NOT dedup semantics.
            // Without the bypass, synthetic family names like "Last1",
            // "Last2" share a soundex and the phonetic-match path 409s
            // after the first success.
            add("X-Confirm-Duplicate", "true")
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

    private companion object {
        /**
         * 50 matches the floor called out in the 4A.2 design-pack
         * refinement #3. Enough to reliably surface contention in
         * the `ON CONFLICT DO UPDATE` path under Testcontainers
         * concurrency; not so many that the test dominates the
         * suite's wall-clock time.
         */
        const val PARALLEL_CREATES: Int = 50
    }
}
