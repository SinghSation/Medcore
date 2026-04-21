package com.medcore.audit

import com.medcore.TestcontainersConfiguration
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Proves the ADR-003 §2 DB-level immutability grant: the `medcore_app`
 * role can INSERT and SELECT audit rows but cannot UPDATE, DELETE, or
 * TRUNCATE them. Real DB failure — not a mocked repository exception.
 *
 * The test superuser-connects once to set a local-only password on the
 * `medcore_app` role (the V7 migration creates the role without a
 * password so the committed migration carries no secret; ops sets the
 * production password out-of-band). A second DataSource connects as
 * `medcore_app` with that password and attempts the forbidden ops.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AuditImmutabilityTest {

    @Autowired
    lateinit var postgres: PostgreSQLContainer<*>

    @Autowired
    lateinit var dataSource: DataSource

    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun reset() {
        jdbc = JdbcTemplate(dataSource)
        jdbc.update("DELETE FROM audit.audit_event")
        // Set a local-only password on medcore_app so the test can log in
        // as that role. This password exists only inside the ephemeral
        // Testcontainers Postgres and is destroyed with the container.
        jdbc.update("ALTER ROLE medcore_app WITH PASSWORD '${APP_ROLE_TEST_PASSWORD}'")
    }

    @Test
    fun `medcore_app can INSERT an audit row`() {
        appRoleJdbc().update(
            """
            INSERT INTO audit.audit_event
                (id, recorded_at, actor_type, action, outcome)
            VALUES (?, ?, 'SYSTEM', 'identity.user.provisioned', 'SUCCESS')
            """.trimIndent(),
            UUID.randomUUID(),
            java.sql.Timestamp.from(Instant.now()),
        )
        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit.audit_event",
            Int::class.java,
        )
        assertEquals(1, count)
    }

    @Test
    fun `medcore_app can SELECT audit rows`() {
        seedAuditRow()
        val rows = appRoleJdbc().queryForList("SELECT id FROM audit.audit_event")
        assertEquals(1, rows.size)
    }

    @Test
    fun `medcore_app UPDATE on audit_event is denied at the DB layer`() {
        seedAuditRow()
        val app = appRoleJdbc()
        val ex = assertThrows(Exception::class.java) {
            app.update("UPDATE audit.audit_event SET outcome = 'DENIED'")
        }
        assertPermissionDenied(ex)
    }

    @Test
    fun `medcore_app DELETE on audit_event is denied at the DB layer`() {
        seedAuditRow()
        val app = appRoleJdbc()
        val ex = assertThrows(Exception::class.java) {
            app.update("DELETE FROM audit.audit_event")
        }
        assertPermissionDenied(ex)
    }

    @Test
    fun `medcore_app TRUNCATE on audit_event is denied at the DB layer`() {
        seedAuditRow()
        val app = appRoleJdbc()
        val ex = assertThrows(Exception::class.java) {
            app.update("TRUNCATE audit.audit_event")
        }
        assertPermissionDenied(ex)
    }

    // ---------------------------------------------------------------- helpers

    private fun seedAuditRow() {
        jdbc.update(
            """
            INSERT INTO audit.audit_event
                (id, recorded_at, actor_type, action, outcome)
            VALUES (?, ?, 'SYSTEM', 'identity.user.provisioned', 'SUCCESS')
            """.trimIndent(),
            UUID.randomUUID(),
            java.sql.Timestamp.from(Instant.now()),
        )
    }

    private fun appRoleJdbc(): JdbcTemplate {
        val ds = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = APP_ROLE_NAME
            password = APP_ROLE_TEST_PASSWORD
        }
        return JdbcTemplate(ds)
    }

    private fun assertPermissionDenied(ex: Throwable) {
        val cause = findSqlCause(ex)
        val message = cause?.message.orEmpty().lowercase()
        assertTrue(
            message.contains("permission denied"),
            "expected 'permission denied' in DB error message, got: ${cause?.message}",
        )
    }

    private fun findSqlCause(throwable: Throwable): SQLException? {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is SQLException) return current
            current = current.cause
        }
        return null
    }

    private companion object {
        const val APP_ROLE_NAME = "medcore_app"
        const val APP_ROLE_TEST_PASSWORD = "medcore_app_testcontainers_only"
    }
}
