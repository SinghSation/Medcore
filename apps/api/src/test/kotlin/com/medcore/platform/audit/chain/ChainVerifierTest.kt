package com.medcore.platform.audit.chain

import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
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
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Real-DB verification of [ChainVerifier] behaviour. Two required
 * scenarios (per Phase 3F.4 DoD):
 *
 *   1. **Clean chain** — verification returns [ChainVerificationResult.Clean]
 *      AND emits no extra log lines in an audit-access-test-sense
 *      (logs verified at scheduler level; here we just assert the
 *      result shape).
 *   2. **Tampered chain** — superuser overrides the grant-based
 *      immutability of `audit.audit_event`, mutates a row's
 *      `reason` column, and asserts the verifier returns
 *      [ChainVerificationResult.Broken] with a non-zero break
 *      count and a known reason code from V9.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class ChainVerifierTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var mockOAuth2Server: MockOAuth2Server

    @Autowired
    lateinit var verifier: ChainVerifier

    @Autowired
    @Qualifier("adminDataSource")
    lateinit var adminDataSource: DataSource

    private lateinit var adminJdbc: JdbcTemplate

    @BeforeEach
    fun reset() {
        adminJdbc = JdbcTemplate(adminDataSource)
        adminJdbc.update("DELETE FROM audit.audit_event")
        adminJdbc.update("DELETE FROM tenancy.tenant_membership")
        adminJdbc.update("DELETE FROM tenancy.tenant")
        adminJdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `empty chain verifies clean`() {
        val result = verifier.verify()
        assertThat(result).isEqualTo(ChainVerificationResult.Clean)
    }

    @Test
    fun `populated valid chain verifies clean with no breaks`() {
        seedAuditRowsViaMeCall()
        val rowCount = adminJdbc.queryForObject(
            "SELECT COUNT(*) FROM audit.audit_event",
            Int::class.java,
        )
        assertThat(rowCount).describedAs("seed produced audit rows").isGreaterThan(0)

        val result = verifier.verify()
        assertThat(result)
            .describedAs("healthy chain must verify clean; result=$result")
            .isEqualTo(ChainVerificationResult.Clean)
    }

    @Test
    fun `tampered row produces Broken result with non-zero count`() {
        seedAuditRowsViaMeCall()

        // Mutate a row's `reason` via the superuser datasource. The
        // stored `row_hash` was computed at append time from the
        // original canonical form; changing any contributing column
        // (reason is one per V9 canonicalisation) makes the stored
        // row_hash no longer match the recomputed hash. verify_chain()
        // detects this as row_hash_mismatch (or, if we pick a later
        // row, as prev_hash_mismatch on its successor).
        val victimId = adminJdbc.queryForObject(
            "SELECT id FROM audit.audit_event ORDER BY sequence_no LIMIT 1",
            UUID::class.java,
        )
        adminJdbc.update(
            "UPDATE audit.audit_event SET reason = 'tampered' WHERE id = ?",
            victimId,
        )

        val result = verifier.verify()
        assertThat(result)
            .describedAs("tampered row must fail verification; result=$result")
            .isInstanceOf(ChainVerificationResult.Broken::class.java)

        val broken = result as ChainVerificationResult.Broken
        assertThat(broken.breakCount)
            .describedAs("verify_chain must report at least one break")
            .isGreaterThanOrEqualTo(1)
        assertThat(broken.firstReason)
            .describedAs("break reason must be a known V9 code")
            .isIn(
                "row_hash_mismatch",
                "prev_hash_mismatch",
                "sequence_gap",
                "sequence_no_null",
                "first_row_has_prev_hash",
            )
    }

    private fun seedAuditRowsViaMeCall() {
        // Driving an authenticated GET /api/v1/me produces
        // identity.user.provisioned + identity.user.login.success
        // (two audit rows per first-time subject) via the live
        // append-event function — the same path the chain is
        // designed to verify.
        val subject = "verify-${UUID.randomUUID()}"
        val headers = HttpHeaders().apply { setBearerAuth(issueToken(subject)) }
        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    private fun issueToken(subject: String): String =
        mockOAuth2Server.issueToken(
            issuerId = MOCK_ISSUER_ID,
            clientId = "medcore-test-client",
            tokenCallback = DefaultOAuth2TokenCallback(
                issuerId = MOCK_ISSUER_ID,
                subject = subject,
                claims = mapOf("email_verified" to true),
            ),
        ).serialize()
}
