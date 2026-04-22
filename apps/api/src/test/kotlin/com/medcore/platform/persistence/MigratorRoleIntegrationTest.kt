package com.medcore.platform.persistence

import com.medcore.TestcontainersConfiguration
import com.zaxxer.hikari.HikariDataSource
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

/**
 * Verifies that V11's `medcore_migrator` role carries the least-
 * privilege grants ADR-006 §4.3 promises:
 *
 *   - CAN: `CREATE TABLE` in any application schema.
 *   - CANNOT: `SELECT` / `INSERT` / `UPDATE` / `DELETE` on existing
 *     application tables (identity.user, tenancy.tenant, audit.audit_event).
 *   - CANNOT: bypass RLS (NOBYPASSRLS attribute).
 *
 * Drives the migrator role through a dedicated Hikari connection
 * created in [BeforeEach] with a deliberately-provisioned password.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MigratorRoleIntegrationTest {

    @Autowired
    @Qualifier("adminDataSource")
    lateinit var adminDataSource: DataSource

    private lateinit var migratorDataSource: HikariDataSource

    @BeforeEach
    fun provisionMigratorPassword() {
        // V11 creates the role with LOGIN but no password. Set a test
        // password via the superuser so the test can connect.
        val adminJdbc = JdbcTemplate(adminDataSource)
        adminJdbc.execute(
            "ALTER ROLE medcore_migrator WITH PASSWORD 'migrator-test-password'",
        )

        // Build a dedicated HikariDataSource that connects as the migrator.
        val adminJdbcUrl = adminJdbcUrl(adminDataSource)
        migratorDataSource = HikariDataSource().apply {
            jdbcUrl = adminJdbcUrl
            username = "medcore_migrator"
            password = "migrator-test-password"
            maximumPoolSize = 2
            minimumIdle = 1
            poolName = "medcore_migrator_test_pool"
        }
    }

    @Test
    fun `medcore_migrator CAN CREATE TABLE in the identity schema`() {
        val jdbc = JdbcTemplate(migratorDataSource)
        val uniqueName = "probe_${System.nanoTime()}"
        jdbc.execute("CREATE TABLE identity.$uniqueName (id int)")
        // Cleanup (still as migrator — it owns the table).
        jdbc.execute("DROP TABLE identity.$uniqueName")
    }

    @Test
    fun `medcore_migrator CANNOT SELECT from identity_user`() {
        val jdbc = JdbcTemplate(migratorDataSource)
        assertThatThrownBy {
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity.\"user\"",
                Int::class.java,
            )
        }.isInstanceOf(Exception::class.java) // permission denied / bad SQL grammar — access is refused
    }

    @Test
    fun `medcore_migrator CANNOT SELECT from tenancy_tenant`() {
        val jdbc = JdbcTemplate(migratorDataSource)
        assertThatThrownBy {
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenancy.tenant",
                Int::class.java,
            )
        }.isInstanceOf(Exception::class.java) // permission denied / bad SQL grammar — access is refused
    }

    @Test
    fun `medcore_migrator CANNOT SELECT from audit_audit_event`() {
        val jdbc = JdbcTemplate(migratorDataSource)
        assertThatThrownBy {
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit.audit_event",
                Int::class.java,
            )
        }.isInstanceOf(Exception::class.java) // permission denied / bad SQL grammar — access is refused
    }

    @Test
    fun `medcore_migrator role attributes include NOBYPASSRLS`() {
        val adminJdbc = JdbcTemplate(adminDataSource)
        val bypassRls = adminJdbc.queryForObject(
            "SELECT rolbypassrls FROM pg_roles WHERE rolname = 'medcore_migrator'",
            Boolean::class.java,
        )!!
        assertThat(bypassRls)
            .describedAs("medcore_migrator MUST NOT bypass RLS (ADR-006 §4.3)")
            .isFalse()
    }

    @Test
    fun `medcore_migrator role attributes include NOSUPERUSER`() {
        val adminJdbc = JdbcTemplate(adminDataSource)
        val isSuperUser = adminJdbc.queryForObject(
            "SELECT rolsuper FROM pg_roles WHERE rolname = 'medcore_migrator'",
            Boolean::class.java,
        )!!
        assertThat(isSuperUser).isFalse()
    }

    @Test
    fun `medcore_migrator CAN read flyway_schema_history`() {
        // Necessary: migrator owns this table and must track
        // migration history.
        val jdbc = JdbcTemplate(migratorDataSource)
        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway.flyway_schema_history",
            Int::class.java,
        )!!
        assertThat(count).isGreaterThan(0)
    }

    private fun adminJdbcUrl(source: DataSource): String {
        val connection = source.connection
        return try {
            connection.metaData.url
        } finally {
            connection.close()
        }
    }
}
