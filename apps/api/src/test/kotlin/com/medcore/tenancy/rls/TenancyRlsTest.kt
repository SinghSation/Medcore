package com.medcore.tenancy.rls

import com.medcore.TestcontainersConfiguration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.PostgreSQLContainer

/**
 * End-to-end RLS verification for the tenancy tables introduced in
 * Phase 3B.1 and protected in Phase 3D (V8). Connects directly as the
 * non-superuser role `medcore_app` so policy evaluation is real (the
 * default container user is a superuser and bypasses RLS — see V8
 * header comment).
 *
 * The test never goes through the running app; it exercises the DB
 * layer directly with explicit GUC values, which is the cleanest way
 * to demonstrate that the policies enforce the invariant regardless
 * of any service-layer filtering.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TenancyRlsTest {

    @Autowired
    lateinit var postgres: PostgreSQLContainer<*>

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private lateinit var superJdbc: JdbcTemplate
    private lateinit var alice: UUID
    private lateinit var bob: UUID
    private lateinit var acme: UUID
    private lateinit var beta: UUID
    private lateinit var archived: UUID

    @BeforeEach
    fun reset() {
        superJdbc = JdbcTemplate(dataSource)
        superJdbc.update("DELETE FROM audit.audit_event")
        superJdbc.update("DELETE FROM tenancy.tenant_membership")
        superJdbc.update("DELETE FROM tenancy.tenant")
        superJdbc.update("DELETE FROM identity.\"user\"")
        superJdbc.update("ALTER ROLE medcore_app WITH PASSWORD '${APP_ROLE_TEST_PASSWORD}'")

        alice = seedUser("alice")
        bob = seedUser("bob")
        acme = seedTenant("acme-health")
        beta = seedTenant("beta-clinic")
        archived = seedTenant("gone-corp", status = "ARCHIVED")

        // Alice: ACTIVE in acme, SUSPENDED in beta, ACTIVE in archived tenant
        seedMembership(acme, alice, status = "ACTIVE")
        seedMembership(beta, alice, status = "SUSPENDED")
        seedMembership(archived, alice, status = "ACTIVE")
        // Bob: ACTIVE in beta only
        seedMembership(beta, bob, status = "ACTIVE")
    }

    @Test
    fun `medcore_app sees only tenants where it has an ACTIVE membership`() {
        withAppRoleTransaction { jdbc ->
            setGucs(jdbc, userId = alice)

            val slugs = jdbc.query(
                "SELECT slug FROM tenancy.tenant ORDER BY slug",
                { rs, _ -> rs.getString("slug") },
            )

            // - acme-health: Alice is ACTIVE → visible
            // - beta-clinic: Alice is SUSPENDED → hidden
            // - gone-corp: Alice is ACTIVE but tenant is ARCHIVED, but the
            //   tenant policy keys on membership.status only (the
            //   ARCHIVED filter happens at the service layer; the
            //   tenant.status filter would be a separate concern). Per
            //   V8's policy comment, this is intentional — the policy
            //   answers "what tenants can the caller see at all", not
            //   "what tenants are usable". So gone-corp DOES appear here.
            assertEquals(listOf("acme-health", "gone-corp"), slugs)
        }
    }

    @Test
    fun `medcore_app sees only its own membership rows`() {
        withAppRoleTransaction { jdbc ->
            setGucs(jdbc, userId = alice)

            val ids = jdbc.query(
                "SELECT user_id::text AS uid FROM tenancy.tenant_membership",
                { rs, _ -> rs.getString("uid") },
            )

            // Alice has 3 rows (acme/beta/archived). Bob's row in beta is filtered out.
            assertEquals(3, ids.size)
            assertEquals(setOf(alice.toString()), ids.toSet())
        }
    }

    @Test
    fun `bob and alice cannot see each other`() {
        withAppRoleTransaction { jdbc ->
            setGucs(jdbc, userId = bob)

            val tenantSlugs = jdbc.query(
                "SELECT slug FROM tenancy.tenant ORDER BY slug",
                { rs, _ -> rs.getString("slug") },
            )
            val membershipUserIds = jdbc.query(
                "SELECT user_id::text AS uid FROM tenancy.tenant_membership",
                { rs, _ -> rs.getString("uid") },
            )

            assertEquals(listOf("beta-clinic"), tenantSlugs)
            assertEquals(setOf(bob.toString()), membershipUserIds.toSet())
        }
    }

    @Test
    fun `missing GUC fails closed — no rows returned for either table`() {
        withAppRoleTransaction { jdbc ->
            // Deliberately do NOT call setGucs — both GUCs read as empty
            val tenantCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenancy.tenant",
                Int::class.java,
            )
            val membershipCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenancy.tenant_membership",
                Int::class.java,
            )
            assertEquals(0, tenantCount, "tenancy.tenant must fail closed without GUC")
            assertEquals(0, membershipCount, "tenancy.tenant_membership must fail closed without GUC")
        }
    }

    @Test
    fun `SUSPENDED membership does not reveal the tenant`() {
        // Alice is SUSPENDED in beta; she should not see beta via the
        // tenant-policy path even though she has a membership row.
        withAppRoleTransaction { jdbc ->
            setGucs(jdbc, userId = alice)
            val betaSeen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenancy.tenant WHERE slug = 'beta-clinic'",
                Int::class.java,
            )
            assertEquals(0, betaSeen)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun setGucs(jdbc: JdbcTemplate, userId: UUID? = null, tenantId: UUID? = null) {
        jdbc.queryForObject(
            "SELECT set_config(?, ?, true)",
            String::class.java,
            "app.current_user_id", userId?.toString().orEmpty(),
        )
        jdbc.queryForObject(
            "SELECT set_config(?, ?, true)",
            String::class.java,
            "app.current_tenant_id", tenantId?.toString().orEmpty(),
        )
    }

    private fun seedUser(subject: String): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        superJdbc.update(
            """
            INSERT INTO identity."user"
                (id, issuer, subject, email_verified, status, created_at, updated_at, row_version)
            VALUES (?, 'rls-test', ?, false, 'ACTIVE', ?, ?, 0)
            """.trimIndent(),
            id, subject,
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
        )
        return id
    }

    private fun seedTenant(slug: String, status: String = "ACTIVE"): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        superJdbc.update(
            """
            INSERT INTO tenancy.tenant
                (id, slug, display_name, status, created_at, updated_at, row_version)
            VALUES (?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
            id, slug, "T $slug", status,
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
        )
        return id
    }

    private fun seedMembership(
        tenant: UUID,
        user: UUID,
        role: String = "MEMBER",
        status: String = "ACTIVE",
    ): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        superJdbc.update(
            """
            INSERT INTO tenancy.tenant_membership
                (id, tenant_id, user_id, role, status, created_at, updated_at, row_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
            id, tenant, user, role, status,
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
        )
        return id
    }

    private fun appRoleDataSource(): DataSource =
        DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = APP_ROLE_NAME
            password = APP_ROLE_TEST_PASSWORD
        }

    private fun withAppRoleTransaction(block: (JdbcTemplate) -> Unit) {
        // Use a programmatic transaction on the medcore_app datasource.
        // SET LOCAL requires a tx; we manage the tx ourselves so the
        // GUC and the SELECTs hit the same connection.
        val ds = appRoleDataSource()
        val txm = org.springframework.jdbc.datasource.DataSourceTransactionManager(ds)
        val txTemplate = TransactionTemplate(txm)
        txTemplate.executeWithoutResult {
            block(JdbcTemplate(ds))
        }
    }

    private companion object {
        const val APP_ROLE_NAME = "medcore_app"
        const val APP_ROLE_TEST_PASSWORD = "medcore_app_testcontainers_only"
    }
}
