package com.medcore.tenancy.rls

import com.medcore.TestcontainersConfiguration
import com.medcore.platform.persistence.TenancySessionContext
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Verifies the load-bearing GUC lifecycle invariants:
 *
 *   1. `apply()` outside a transaction throws — the SET LOCAL contract
 *      requires an active tx; calling outside risks GUC leakage to
 *      pooled connections.
 *   2. Within a transaction, the GUCs are visible.
 *   3. After the transaction commits, the next transaction on the
 *      SAME connection sees the GUCs reset to empty (this is the
 *      no-leakage guarantee that pooled connections rely on).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TenancyGucLifecycleTest {

    @Autowired
    lateinit var sessionContext: TenancySessionContext

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `apply outside a transaction is rejected`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            sessionContext.apply(userId = UUID.randomUUID(), tenantId = null)
        }
        assert(ex.message!!.contains("active transaction")) {
            "expected an explicit error about active transaction; got: ${ex.message}"
        }
    }

    @Test
    fun `apply inside a transaction sets both GUCs`() {
        val tx = TransactionTemplate(transactionManager)
        val userId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()

        val seenUser: String? = tx.execute {
            sessionContext.apply(userId = userId, tenantId = tenantId)
            JdbcTemplate(dataSource).queryForObject(
                "SELECT current_setting('app.current_user_id', true)",
                String::class.java,
            )
        }
        assertEquals(userId.toString(), seenUser)

        val seenTenant: String? = tx.execute {
            sessionContext.apply(userId = userId, tenantId = tenantId)
            JdbcTemplate(dataSource).queryForObject(
                "SELECT current_setting('app.current_tenant_id', true)",
                String::class.java,
            )
        }
        assertEquals(tenantId.toString(), seenTenant)
    }

    @Test
    fun `GUC values do not leak across transactions on pooled connections`() {
        val tx = TransactionTemplate(transactionManager)
        val first = UUID.randomUUID()

        // Transaction A — set GUC
        tx.executeWithoutResult {
            sessionContext.apply(userId = first, tenantId = null)
            val seen = JdbcTemplate(dataSource).queryForObject(
                "SELECT current_setting('app.current_user_id', true)",
                String::class.java,
            )
            assertEquals(first.toString(), seen)
        }

        // Transaction B — do NOT call apply; SET LOCAL must have reset
        // at A's commit, so the GUC reads as empty.
        val seenAfter: String? = tx.execute {
            JdbcTemplate(dataSource).queryForObject(
                "SELECT current_setting('app.current_user_id', true)",
                String::class.java,
            )
        }
        // Empty string (not null) when missing_ok=true and GUC unset.
        assertEquals("", seenAfter)
    }

    @Test
    fun `null userId clears the effective filter — fail-closed downstream`() {
        val tx = TransactionTemplate(transactionManager)
        val seen: String? = tx.execute {
            sessionContext.apply(userId = null, tenantId = null)
            JdbcTemplate(dataSource).queryForObject(
                "SELECT NULLIF(current_setting('app.current_user_id', true), '')",
                String::class.java,
            )
        }
        // NULLIF('', '') = NULL → policies fail closed.
        assertNull(seen)
    }
}
