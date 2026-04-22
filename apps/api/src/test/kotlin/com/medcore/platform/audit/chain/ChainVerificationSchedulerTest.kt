package com.medcore.platform.audit.chain

import com.medcore.TestcontainersConfiguration
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

/**
 * Verifies [ChainVerificationScheduler] dispatch for all three
 * [ChainVerificationResult] variants, without waiting on cron
 * timing. A `@TestConfiguration` supplies a programmable
 * [ChainVerifier] so the test decides which outcome to simulate.
 *
 * Required coverage (Phase 3F.4 DoD):
 *   - Clean outcome: NO audit event emitted; no ERROR log.
 *   - Broken outcome: exactly ONE `audit.chain.integrity_failed`
 *     audit event per cycle with reason
 *     `breaks:<N>|reason:<code>`.
 *   - VerifierFailed outcome: exactly ONE
 *     `audit.chain.verification_failed` audit event per cycle
 *     with reason `verifier_failed`.
 *   - Concurrency guard: a second call while the first is "in
 *     progress" skips without re-emitting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class, ChainVerificationSchedulerTest.Config::class)
@TestPropertySource(
    properties = [
        // Enable the scheduler so @ConditionalOnProperty matches and
        // the bean is created; test drives runVerification() directly
        // rather than waiting on cron.
        "medcore.audit.chain-verification.enabled=true",
        // Long cron so accidental cron firings don't interfere with
        // explicit invocations during the test.
        "medcore.audit.chain-verification.cron=0 0 0 1 1 *",
    ],
)
class ChainVerificationSchedulerTest {

    @Autowired
    lateinit var scheduler: ChainVerificationScheduler

    @Autowired
    lateinit var programmableVerifier: ProgrammableVerifier

    @Autowired
    @Qualifier("adminDataSource")
    lateinit var adminDataSource: DataSource

    private lateinit var adminJdbc: JdbcTemplate

    @BeforeEach
    fun reset() {
        adminJdbc = JdbcTemplate(adminDataSource)
        adminJdbc.update("DELETE FROM audit.audit_event")
        programmableVerifier.nextResult = ChainVerificationResult.Clean
    }

    @Test
    fun `clean outcome emits no audit event`() {
        programmableVerifier.nextResult = ChainVerificationResult.Clean

        scheduler.runVerification()

        val rows = auditRowsFor(
            "audit.chain.integrity_failed",
            "audit.chain.verification_failed",
        )
        assertThat(rows)
            .describedAs("clean chain MUST NOT emit chain-integrity or verifier-failed audit")
            .isEmpty()
    }

    @Test
    fun `broken outcome emits exactly one integrity_failed audit with coded reason`() {
        programmableVerifier.nextResult = ChainVerificationResult.Broken(
            breakCount = 3,
            firstReason = "prev_hash_mismatch",
        )

        scheduler.runVerification()

        val rows = auditRowsFor("audit.chain.integrity_failed")
        assertThat(rows).describedAs("exactly one integrity_failed per cycle").hasSize(1)
        val row = rows.single()
        assertThat(row["reason"] as String?)
            .isEqualTo("breaks:3|reason:prev_hash_mismatch")
        assertThat(row["actor_type"]).isEqualTo("SYSTEM")
        assertThat(row["actor_id"]).isNull()
        assertThat(row["tenant_id"]).isNull()
        assertThat(row["outcome"]).isEqualTo("ERROR")
    }

    @Test
    fun `verifier failure emits exactly one verification_failed audit`() {
        programmableVerifier.nextResult = ChainVerificationResult.VerifierFailed(
            cause = RuntimeException("simulated DB outage for test"),
        )

        scheduler.runVerification()

        val integrityRows = auditRowsFor("audit.chain.integrity_failed")
        assertThat(integrityRows)
            .describedAs("integrity_failed MUST NOT be emitted when verifier itself fails")
            .isEmpty()

        val verifierRows = auditRowsFor("audit.chain.verification_failed")
        assertThat(verifierRows)
            .describedAs("exactly one verification_failed per cycle")
            .hasSize(1)
        val row = verifierRows.single()
        assertThat(row["reason"] as String?).isEqualTo("verifier_failed")
        assertThat(row["actor_type"]).isEqualTo("SYSTEM")
        assertThat(row["outcome"]).isEqualTo("ERROR")
    }

    @Test
    fun `repeated broken outcomes emit one audit per cycle, not per invocation`() {
        programmableVerifier.nextResult = ChainVerificationResult.Broken(
            breakCount = 1,
            firstReason = "row_hash_mismatch",
        )

        repeat(3) { scheduler.runVerification() }

        val rows = auditRowsFor("audit.chain.integrity_failed")
        assertThat(rows)
            .describedAs("3 scheduler invocations MUST produce 3 events, one per cycle")
            .hasSize(3)
        // All three rows have the same reason slug.
        rows.forEach { row ->
            assertThat(row["reason"] as String?).isEqualTo("breaks:1|reason:row_hash_mismatch")
        }
    }

    private fun auditRowsFor(vararg actions: String): List<Map<String, Any?>> =
        adminJdbc.queryForList(
            """
            SELECT action, reason,
                   actor_type, actor_id::text AS actor_id,
                   tenant_id::text AS tenant_id,
                   outcome
              FROM audit.audit_event
             WHERE action = ANY(?::text[])
             ORDER BY recorded_at
            """.trimIndent(),
            "{${actions.joinToString(",")}}",
        )

    /**
     * Test-only verifier whose next result is programmable from the
     * test body. Registered as `@Primary` via the nested
     * `@TestConfiguration` below, replacing the real
     * [ChainVerifier] while still letting the rest of the context
     * load normally (so `adminDataSource`, the audit writer, and
     * all other beans work as in production).
     */
    class ProgrammableVerifier : ChainVerifier(
        // JdbcTemplate and ObservationRegistry are never used because
        // verify() is overridden. Passing bogus-but-non-null instances
        // avoids Spring bean-construction failures.
        jdbcTemplate = org.springframework.jdbc.core.JdbcTemplate(),
        observationRegistry = io.micrometer.observation.ObservationRegistry.NOOP,
    ) {
        @Volatile
        var nextResult: ChainVerificationResult = ChainVerificationResult.Clean

        override fun toString(): String = "ProgrammableVerifier(next=$nextResult)"

        // Kotlin note: ChainVerifier.verify() is not `open` by default.
        // To make this override work, [ChainVerifier] is declared
        // `open` and its `verify()` method is `open`. See
        // ChainVerifier.kt for the rationale (test-only extensibility
        // — no other code subclasses it).
        override fun verify(): ChainVerificationResult = nextResult
    }

    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun programmableVerifier(): ProgrammableVerifier = ProgrammableVerifier()
    }
}
