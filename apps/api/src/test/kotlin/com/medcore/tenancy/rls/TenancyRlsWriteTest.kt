package com.medcore.tenancy.rls

import com.medcore.TestcontainersConfiguration
import com.zaxxer.hikari.HikariDataSource
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
import org.springframework.transaction.support.TransactionTemplate

/**
 * Proves V12's RLS WRITE policies enforce tenant isolation even
 * when the application authz layer is bypassed (Phase 3J,
 * ADR-007 §4.4).
 *
 * Strategy:
 *   1. Seed: two tenants (A and B) + three users (Alice owner of A,
 *      Bob owner of B, Carol is a stranger), via the adminDataSource.
 *   2. For each attempted write, connect directly as `medcore_app`,
 *      set the `app.current_user_id` GUC to the caller's user id,
 *      attempt the cross-tenant write, and assert Postgres refuses.
 *
 * The test bypasses every application-layer authz check — this is
 * the last line of defence, evaluated by Postgres itself regardless
 * of what the app does or does not check.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TenancyRlsWriteTest {

    @Autowired
    @Qualifier("adminDataSource")
    lateinit var adminDataSource: DataSource

    @Autowired
    @Qualifier("appDataSource")
    lateinit var appDataSource: DataSource

    @Autowired
    lateinit var txManager: PlatformTransactionManager

    private val aliceId = UUID.randomUUID()
    private val bobId = UUID.randomUUID()
    private val carolId = UUID.randomUUID()
    private lateinit var tenantAId: UUID
    private lateinit var tenantBId: UUID

    @BeforeEach
    fun seed() {
        val adminJdbc = JdbcTemplate(adminDataSource)
        adminJdbc.update("DELETE FROM audit.audit_event")
        adminJdbc.update("DELETE FROM clinical.patient_identifier")
        adminJdbc.update("DELETE FROM clinical.patient")
        adminJdbc.update("DELETE FROM tenancy.tenant_membership")
        adminJdbc.update("DELETE FROM tenancy.tenant")
        adminJdbc.update("DELETE FROM identity.\"user\"")

        listOf(aliceId, bobId, carolId).forEach { uid ->
            adminJdbc.update(
                """
                INSERT INTO identity."user"(id, issuer, subject, email_verified, status, created_at, updated_at, row_version)
                VALUES (?, 'http://localhost/', ?::text, false, 'ACTIVE', NOW(), NOW(), 0)
                """.trimIndent(),
                uid, uid.toString(),
            )
        }

        tenantAId = UUID.randomUUID()
        tenantBId = UUID.randomUUID()
        adminJdbc.update(
            """
            INSERT INTO tenancy.tenant(id, slug, display_name, status, created_at, updated_at, row_version)
                 VALUES (?, 'tenant-a', 'Tenant A', 'ACTIVE', NOW(), NOW(), 0),
                        (?, 'tenant-b', 'Tenant B', 'ACTIVE', NOW(), NOW(), 0)
            """.trimIndent(),
            tenantAId, tenantBId,
        )
        adminJdbc.update(
            """
            INSERT INTO tenancy.tenant_membership(id, tenant_id, user_id, role, status, created_at, updated_at, row_version)
                 VALUES (?, ?, ?, 'OWNER',  'ACTIVE', NOW(), NOW(), 0),
                        (?, ?, ?, 'OWNER',  'ACTIVE', NOW(), NOW(), 0)
            """.trimIndent(),
            UUID.randomUUID(), tenantAId, aliceId,
            UUID.randomUUID(), tenantBId, bobId,
        )
    }

    @Test
    fun `medcore_app cannot INSERT into tenant (policy WITH CHECK false)`() {
        runAsAppWithUser(aliceId) { jdbc ->
            // Even Alice (owner of tenant A) cannot INSERT a new
            // tenant via medcore_app — system-scope creation goes
            // through the SECURITY DEFINER bootstrap function only.
            assertThatThrownBy {
                jdbc.update(
                    """
                    INSERT INTO tenancy.tenant(id, slug, display_name, status, created_at, updated_at, row_version)
                         VALUES (?, 'tenant-c', 'Tenant C', 'ACTIVE', NOW(), NOW(), 0)
                    """.trimIndent(),
                    UUID.randomUUID(),
                )
            }.isInstanceOf(Exception::class.java) // RLS policy rejects
        }
    }

    @Test
    fun `medcore_app CAN UPDATE its own tenant as OWNER`() {
        val rows = runAsAppWithUser(aliceId) { jdbc ->
            jdbc.update(
                "UPDATE tenancy.tenant SET display_name = 'Tenant A Renamed' WHERE id = ?",
                tenantAId,
            )
        }
        assertThat(rows).isEqualTo(1)
    }

    @Test
    fun `medcore_app CANNOT UPDATE a tenant they do not belong to`() {
        // Alice tries to rename Bob's tenant. RLS USING clause
        // fails → zero rows updated (Postgres RLS silently
        // filters rows without WITH CHECK violation on UPDATE).
        val rows = runAsAppWithUser(aliceId) { jdbc ->
            jdbc.update(
                "UPDATE tenancy.tenant SET display_name = 'Hijacked' WHERE id = ?",
                tenantBId,
            )
        }
        assertThat(rows)
            .describedAs("RLS must hide the row from a cross-tenant UPDATE")
            .isEqualTo(0)
    }

    @Test
    fun `medcore_app CANNOT UPDATE any tenant without a valid GUC`() {
        // No GUC = fail-closed (USING uses NULLIF(...)::uuid).
        val rows = runAsApp { jdbc ->
            jdbc.update(
                "UPDATE tenancy.tenant SET display_name = 'No Context' WHERE id = ?",
                tenantAId,
            )
        }
        assertThat(rows).isEqualTo(0)
    }

    @Test
    fun `carol has no membership — updates to either tenant return zero rows`() {
        val rowsA = runAsAppWithUser(carolId) { jdbc ->
            jdbc.update(
                "UPDATE tenancy.tenant SET display_name = 'x' WHERE id = ?",
                tenantAId,
            )
        }
        val rowsB = runAsAppWithUser(carolId) { jdbc ->
            jdbc.update(
                "UPDATE tenancy.tenant SET display_name = 'y' WHERE id = ?",
                tenantBId,
            )
        }
        assertThat(rowsA).isZero()
        assertThat(rowsB).isZero()
    }

    @Test
    fun `membership INSERT fails for a non-owner of the target tenant`() {
        runAsAppWithUser(bobId) { jdbc ->
            // Bob tries to add a member to Alice's tenant. WITH
            // CHECK fails → exception, not silent zero rows.
            assertThatThrownBy {
                jdbc.update(
                    """
                    INSERT INTO tenancy.tenant_membership(
                        id, tenant_id, user_id, role, status, created_at, updated_at, row_version
                    )
                    VALUES (?, ?, ?, 'MEMBER', 'ACTIVE', NOW(), NOW(), 0)
                    """.trimIndent(),
                    UUID.randomUUID(), tenantAId, UUID.randomUUID(),
                )
            }.isInstanceOf(Exception::class.java)
        }
    }

    @Test
    fun `bootstrap_create_tenant is NOT callable by medcore_app`() {
        runAsApp { jdbc ->
            // Spring wraps the raw PSQLException ("permission denied
            // for function bootstrap_create_tenant") in a
            // BadSqlGrammarException; the underlying cause chain
            // carries the specific message. Any exception from this
            // call confirms the REVOKE on EXECUTE took effect.
            assertThatThrownBy {
                jdbc.queryForObject(
                    "SELECT tenancy.bootstrap_create_tenant('boot-c', 'Bootstrap C', ?)",
                    UUID::class.java,
                    UUID.randomUUID(),
                )
            }.isInstanceOf(Exception::class.java)
        }
    }

    // --- helpers ---

    private fun <R> runAsApp(action: (JdbcTemplate) -> R): R {
        val jdbc = JdbcTemplate(appDataSource)
        val template = TransactionTemplate(txManager)
        return template.execute { action(jdbc) }!!
    }

    private fun <R> runAsAppWithUser(userId: UUID, action: (JdbcTemplate) -> R): R {
        val jdbc = JdbcTemplate(appDataSource)
        val template = TransactionTemplate(txManager)
        return template.execute {
            // set_config returns the value — use queryForObject, not
            // update, to consume the result cleanly.
            jdbc.queryForObject(
                "SELECT set_config('app.current_user_id', ?, true)",
                String::class.java,
                userId.toString(),
            )
            action(jdbc)
        }!!
    }
}
