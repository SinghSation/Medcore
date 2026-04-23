package com.medcore.platform.security.phi

import com.medcore.TestcontainersConfiguration
import com.medcore.platform.persistence.TenancySessionContext
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Integration coverage for [PhiSessionContext] (Phase 4A.0).
 *
 * Uses Testcontainers Postgres so the GUC-set + GUC-read round
 * trip is exercised against the real driver.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PhiSessionContextTest {

    @Autowired
    lateinit var phiSessionContext: PhiSessionContext

    @Autowired
    lateinit var phiRequestContextHolder: PhiRequestContextHolder

    @Autowired
    lateinit var txManager: PlatformTransactionManager

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @AfterEach
    fun clearHolder() {
        phiRequestContextHolder.clear()
    }

    @Test
    fun `applyFromRequest sets both RLS GUCs inside the transaction`() {
        val userId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        phiRequestContextHolder.set(PhiRequestContext(userId, tenantId))

        val result = TransactionTemplate(txManager).execute {
            phiSessionContext.applyFromRequest()
            val userGuc = jdbcTemplate.queryForObject(
                "SELECT current_setting('app.current_user_id', true)",
                String::class.java,
            )
            val tenantGuc = jdbcTemplate.queryForObject(
                "SELECT current_setting('app.current_tenant_id', true)",
                String::class.java,
            )
            Pair(userGuc, tenantGuc)
        }!!

        assertThat(result.first).isEqualTo(userId.toString())
        assertThat(result.second).isEqualTo(tenantId.toString())
    }

    @Test
    fun `applyFromRequest without holder throws PhiContextMissingException`() {
        // Holder is empty (cleared in @AfterEach of prior test, or
        // never set).
        TransactionTemplate(txManager).execute {
            assertThatThrownBy { phiSessionContext.applyFromRequest() }
                .isInstanceOf(PhiContextMissingException::class.java)
                .hasMessageContaining("PhiRequestContext")
            null
        }
    }

    @Test
    fun `applyFromRequest without active transaction throws`() {
        // TenancySessionContext.apply() requires an active tx;
        // calling applyFromRequest outside one must propagate.
        phiRequestContextHolder.set(
            PhiRequestContext(UUID.randomUUID(), UUID.randomUUID()),
        )
        assertThatThrownBy { phiSessionContext.applyFromRequest() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("active transaction")
    }
}
