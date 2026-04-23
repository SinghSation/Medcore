package com.medcore.clinical.patient.mrn

import com.medcore.TestcontainersConfiguration
import com.medcore.platform.persistence.TenancySessionContext
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate

/**
 * End-to-end verification of [MrnGenerator] against a real
 * Testcontainers Postgres (Phase 4A.2).
 *
 * Covers the four properties this component must guarantee:
 *
 *   1. **Bootstrap** — first mint in a fresh tenant plants the
 *      counter row with defaults, returns `"000001"`.
 *   2. **Monotonic increment** — sequential mints return
 *      `"000001"`, `"000002"`, ... in order.
 *   3. **Tenant isolation** — two tenants' counters are
 *      independent; each starts at 1.
 *   4. **Rollback safety** — if the caller's transaction aborts
 *      after a mint, the counter bump rolls back with it. The
 *      next mint returns the same value the aborted tx would
 *      have used. **NORMATIVE — design-pack refinement #4
 *      ("Add test ensuring MRN is not consumed if transaction
 *      fails").**
 *
 * The test bypasses WriteGate and invokes the generator directly
 * inside a `TransactionTemplate`-owned tx, with RLS GUCs set via
 * [TenancySessionContext] to simulate the runtime `PhiRlsTxHook`
 * behaviour. This keeps the test focused on generator semantics —
 * end-to-end exercise through the controller lands in
 * `CreatePatientIntegrationTest`.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MrnGeneratorTest {

    @Autowired
    lateinit var mrnGenerator: MrnGenerator

    @Autowired
    lateinit var tenancySessionContext: TenancySessionContext

    @Autowired
    lateinit var txManager: PlatformTransactionManager

    @Autowired
    @Qualifier("adminDataSource")
    lateinit var adminDataSource: DataSource

    private val aliceId = UUID.randomUUID()
    private lateinit var tenantA: UUID
    private lateinit var tenantB: UUID

    @BeforeEach
    fun seed() {
        val admin = JdbcTemplate(adminDataSource)
        admin.update("DELETE FROM clinical.patient_mrn_counter")
        admin.update("DELETE FROM clinical.patient_identifier")
        admin.update("DELETE FROM clinical.patient")
        admin.update("DELETE FROM audit.audit_event")
        admin.update("DELETE FROM tenancy.tenant_membership")
        admin.update("DELETE FROM tenancy.tenant")
        admin.update("DELETE FROM identity.\"user\"")

        admin.update(
            """
            INSERT INTO identity."user"(
                id, issuer, subject, email_verified, status,
                created_at, updated_at, row_version
            ) VALUES (?, 'http://localhost/', ?::text, false, 'ACTIVE', NOW(), NOW(), 0)
            """.trimIndent(),
            aliceId, aliceId.toString(),
        )
        tenantA = UUID.randomUUID()
        tenantB = UUID.randomUUID()
        admin.update(
            """
            INSERT INTO tenancy.tenant(
                id, slug, display_name, status, created_at, updated_at, row_version
            ) VALUES
                (?, 'tenant-a', 'Tenant A', 'ACTIVE', NOW(), NOW(), 0),
                (?, 'tenant-b', 'Tenant B', 'ACTIVE', NOW(), NOW(), 0)
            """.trimIndent(),
            tenantA, tenantB,
        )
        admin.update(
            """
            INSERT INTO tenancy.tenant_membership(
                id, tenant_id, user_id, role, status,
                created_at, updated_at, row_version
            ) VALUES
                (?, ?, ?, 'OWNER', 'ACTIVE', NOW(), NOW(), 0),
                (?, ?, ?, 'OWNER', 'ACTIVE', NOW(), NOW(), 0)
            """.trimIndent(),
            UUID.randomUUID(), tenantA, aliceId,
            UUID.randomUUID(), tenantB, aliceId,
        )
    }

    @Test
    fun `first mint for a fresh tenant returns zero-padded 000001`() {
        val mrn = withPhiContext(tenantA) { mrnGenerator.generate(tenantA) }
        assertThat(mrn).isEqualTo("000001")
    }

    @Test
    fun `sequential mints on the same tenant return monotonic zero-padded MRNs`() {
        val first  = withPhiContext(tenantA) { mrnGenerator.generate(tenantA) }
        val second = withPhiContext(tenantA) { mrnGenerator.generate(tenantA) }
        val third  = withPhiContext(tenantA) { mrnGenerator.generate(tenantA) }

        assertThat(first).isEqualTo("000001")
        assertThat(second).isEqualTo("000002")
        assertThat(third).isEqualTo("000003")
    }

    @Test
    fun `counters are tenant-isolated — tenant B starts at 1 regardless of tenant A state`() {
        withPhiContext(tenantA) { mrnGenerator.generate(tenantA) }
        withPhiContext(tenantA) { mrnGenerator.generate(tenantA) }
        withPhiContext(tenantA) { mrnGenerator.generate(tenantA) } // A is now at 3

        val bFirst = withPhiContext(tenantB) { mrnGenerator.generate(tenantB) }
        assertThat(bFirst).isEqualTo("000001")

        // And A continues from where it left off — independently.
        val aFourth = withPhiContext(tenantA) { mrnGenerator.generate(tenantA) }
        assertThat(aFourth).isEqualTo("000004")
    }

    @Test
    fun `rollback in the caller's tx rolls back the counter bump — MRN is NOT consumed`() {
        // Baseline: tenantA's counter does not yet exist.
        val admin = JdbcTemplate(adminDataSource)
        val initialCount = admin.queryForObject(
            "SELECT COUNT(*) FROM clinical.patient_mrn_counter WHERE tenant_id = ?",
            Int::class.java,
            tenantA,
        )
        assertThat(initialCount).isZero()

        // Run a tx that mints an MRN then throws. The TransactionTemplate
        // catches the throw, rolls back, and re-throws.
        val thrown = runCatching {
            TransactionTemplate(txManager).execute(TransactionCallback<Unit> { _: TransactionStatus ->
                tenancySessionContext.apply(userId = aliceId, tenantId = tenantA)
                mrnGenerator.generate(tenantA) // would return "000001" if committed
                throw IllegalStateException("simulated failure — force rollback")
            })
        }
        assertThat(thrown.isFailure)
            .describedAs("tx should have propagated the forced throw")
            .isTrue()

        // Counter table has no row for tenantA: rollback erased the
        // INSERT-branch of the ON CONFLICT upsert.
        val postRollbackCount = admin.queryForObject(
            "SELECT COUNT(*) FROM clinical.patient_mrn_counter WHERE tenant_id = ?",
            Int::class.java,
            tenantA,
        )
        assertThat(postRollbackCount)
            .describedAs("rollback must un-do the counter INSERT — MRN not consumed")
            .isZero()

        // And the next mint returns "000001" — the aborted tx did not
        // advance the sequence.
        val nextMrn = withPhiContext(tenantA) { mrnGenerator.generate(tenantA) }
        assertThat(nextMrn).isEqualTo("000001")
    }

    @Test
    fun `rollback after the first committed mint rolls back ONLY the second increment`() {
        // Commit tenantA's first mint.
        val first = withPhiContext(tenantA) { mrnGenerator.generate(tenantA) }
        assertThat(first).isEqualTo("000001")

        // Second mint inside a tx that then aborts — counter in DB
        // should remain at 1 after rollback.
        val thrown = runCatching {
            TransactionTemplate(txManager).execute(TransactionCallback<Unit> { _: TransactionStatus ->
                tenancySessionContext.apply(userId = aliceId, tenantId = tenantA)
                mrnGenerator.generate(tenantA) // would have been "000002"
                throw IllegalStateException("simulated failure")
            })
        }
        assertThat(thrown.isFailure).isTrue()

        // The third mint (next committed call) returns "000002" — the
        // aborted mint did not advance the counter.
        val third = withPhiContext(tenantA) { mrnGenerator.generate(tenantA) }
        assertThat(third)
            .describedAs("aborted mint must NOT have consumed MRN 000002")
            .isEqualTo("000002")
    }

    // ---- helpers ----

    private fun <R> withPhiContext(tenantId: UUID, block: () -> R): R {
        val template = TransactionTemplate(txManager)
        return template.execute {
            tenancySessionContext.apply(userId = aliceId, tenantId = tenantId)
            block()
        }!!
    }

    @Suppress("unused") // Reserved for later timestamp-shaped seed rows.
    private fun now(): Instant = Instant.now()
}
