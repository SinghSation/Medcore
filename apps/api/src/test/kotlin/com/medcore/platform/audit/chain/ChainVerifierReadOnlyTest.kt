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
 * Asserts [ChainVerifier] performs **no writes** to
 * `audit.audit_event` during a verification round (Phase 3F.4 DoD).
 *
 * Strategy:
 *   1. Seed a non-trivial chain via real HTTP calls (several
 *      authenticated /api/v1/me requests) so the chain has rows to
 *      verify.
 *   2. Snapshot every audit row's (id, sequence_no, row_hash) and
 *      the max sequence_no.
 *   3. Run verification.
 *   4. Re-snapshot. Assert:
 *      - row count is unchanged,
 *      - max sequence_no is unchanged (no new INSERT),
 *      - every (id, sequence_no, row_hash) tuple is byte-for-byte
 *        identical (no UPDATE of any row),
 *      - no row id appears in the after-set that wasn't in the
 *        before-set (no INSERT of a new row),
 *      - no row id disappears (no DELETE).
 *
 * This strictly enforces the "detection only" contract from the 3F.4
 * plan. Any future slice that adds logic to the verifier (e.g., to
 * emit a heartbeat) will FAIL this test and MUST be caught in
 * review.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class ChainVerifierReadOnlyTest {

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
        adminJdbc.update("DELETE FROM clinical.patient_identifier")
        adminJdbc.update("DELETE FROM clinical.patient")
        adminJdbc.update("DELETE FROM tenancy.tenant_membership")
        adminJdbc.update("DELETE FROM tenancy.tenant")
        adminJdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `verification performs no INSERT, no UPDATE, no DELETE on audit event`() {
        // Seed: two distinct subjects, 4 audit rows total
        // (2 x provisioned + login.success per subject).
        repeat(2) { seedOneAuditedRequest() }

        val before = snapshotRows()
        assertThat(before).describedAs("seed produced audit rows").isNotEmpty()
        val beforeMaxSeq = adminJdbc.queryForObject(
            "SELECT MAX(sequence_no) FROM audit.audit_event",
            Long::class.java,
        )

        val result = verifier.verify()
        assertThat(result)
            .describedAs("verification of a healthy chain must return Clean")
            .isEqualTo(ChainVerificationResult.Clean)

        val after = snapshotRows()
        val afterMaxSeq = adminJdbc.queryForObject(
            "SELECT MAX(sequence_no) FROM audit.audit_event",
            Long::class.java,
        )

        assertThat(after)
            .describedAs("row count unchanged (no INSERT, no DELETE)")
            .hasSameSizeAs(before)

        assertThat(afterMaxSeq)
            .describedAs("max sequence_no unchanged (no new INSERT)")
            .isEqualTo(beforeMaxSeq)

        assertThat(after)
            .describedAs("every (id, sequence_no, row_hash) tuple must be byte-identical")
            .containsExactlyElementsOf(before)

        val beforeIds = before.map { it.id }.toSet()
        val afterIds = after.map { it.id }.toSet()
        assertThat(afterIds - beforeIds)
            .describedAs("no new row ids appeared during verification")
            .isEmpty()
        assertThat(beforeIds - afterIds)
            .describedAs("no existing row ids disappeared during verification")
            .isEmpty()
    }

    private data class RowSnapshot(
        val id: UUID,
        val sequenceNo: Long,
        val rowHashHex: String,
    )

    private fun snapshotRows(): List<RowSnapshot> =
        adminJdbc.query(
            """
            SELECT id, sequence_no, encode(row_hash, 'hex') AS row_hash_hex
              FROM audit.audit_event
             ORDER BY sequence_no
            """.trimIndent(),
        ) { rs, _ ->
            RowSnapshot(
                id = rs.getObject("id", UUID::class.java),
                sequenceNo = rs.getLong("sequence_no"),
                rowHashHex = rs.getString("row_hash_hex"),
            )
        }

    private fun seedOneAuditedRequest() {
        val subject = "readonly-${UUID.randomUUID()}"
        val headers = HttpHeaders().apply {
            setBearerAuth(
                mockOAuth2Server.issueToken(
                    issuerId = MOCK_ISSUER_ID,
                    clientId = "medcore-test-client",
                    tokenCallback = DefaultOAuth2TokenCallback(
                        issuerId = MOCK_ISSUER_ID,
                        subject = subject,
                        claims = mapOf("email_verified" to true),
                    ),
                ).serialize(),
            )
        }
        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }
}
