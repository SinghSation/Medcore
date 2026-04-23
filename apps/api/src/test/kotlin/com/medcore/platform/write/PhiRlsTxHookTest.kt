package com.medcore.platform.write

import com.medcore.platform.security.IssuerSubject
import com.medcore.platform.security.MedcorePrincipal
import com.medcore.platform.security.PrincipalStatus
import com.medcore.platform.security.phi.PhiContextMissingException
import com.medcore.platform.security.phi.PhiRequestContext
import com.medcore.platform.security.phi.PhiRequestContextHolder
import com.medcore.platform.security.phi.PhiSessionContext
import com.medcore.TestcontainersConfiguration
import java.time.Instant
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
 * Integration coverage for [PhiRlsTxHook] (Phase 4A.0).
 *
 * Exercises the hook end-to-end via a constructed [WriteGate] —
 * verifies the hook calls [PhiSessionContext.applyFromRequest]
 * inside the gate's transaction, which in turn sets both GUCs
 * visible to the handler's subsequent SQL.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PhiRlsTxHookTest {

    @Autowired
    lateinit var phiRlsTxHook: PhiRlsTxHook

    @Autowired
    lateinit var phiRequestContextHolder: PhiRequestContextHolder

    @Autowired
    lateinit var txManager: PlatformTransactionManager

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val principal = MedcorePrincipal(
        userId = UUID.randomUUID(),
        issuerSubject = IssuerSubject(issuer = "http://localhost/", subject = "alice"),
        email = null,
        emailVerified = true,
        displayName = null,
        preferredUsername = null,
        status = PrincipalStatus.ACTIVE,
        issuedAt = Instant.now(),
        expiresAt = Instant.now().plusSeconds(60),
    )
    private val context = WriteContext(principal = principal)

    @AfterEach
    fun clear() {
        phiRequestContextHolder.clear()
    }

    @Test
    fun `beforeExecute applies PHI GUCs from holder inside transaction`() {
        val userId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        phiRequestContextHolder.set(PhiRequestContext(userId, tenantId))

        val result = TransactionTemplate(txManager).execute {
            phiRlsTxHook.beforeExecute(context)
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
    fun `beforeExecute without holder throws PhiContextMissingException`() {
        TransactionTemplate(txManager).execute {
            assertThatThrownBy { phiRlsTxHook.beforeExecute(context) }
                .isInstanceOf(PhiContextMissingException::class.java)
            null
        }
    }
}
