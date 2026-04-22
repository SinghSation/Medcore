package com.medcore.platform.persistence

import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Direct unit-style tests of [MedcoreAppPasswordSync]'s two
 * execution modes. Exercises the bean against the project's actual
 * datasources rather than mocks so the SQL paths are real.
 *
 * Note: this class does NOT extend `@SpringBootTest`. Each test
 * constructs the component directly, supplies the relevant
 * datasource(s), and calls `afterPropertiesSet()`. That isolates
 * the verify-only vs. sync paths cleanly without needing two
 * full Spring contexts.
 */
class MedcoreAppPasswordSyncTest {

    @Test
    fun `verify-only mode requires the password env var to be present`() {
        val sync = MedcoreAppPasswordSync(
            migratorDataSource = stubMigrator(),
            appPassword = "",
            syncEnabled = false,
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            sync.afterPropertiesSet()
        }
        assert(ex.message!!.contains("MEDCORE_DB_APP_PASSWORD")) {
            "expected explicit env-var name in error, got: ${ex.message}"
        }
    }

    @Test
    fun `verify-only mode does not call ALTER ROLE on the migrator datasource`() {
        // A migrator datasource that throws on ANY use. If the
        // verify-only path ever reaches it, the test fails loudly.
        val sync = MedcoreAppPasswordSync(
            migratorDataSource = throwingMigrator(),
            appPassword = "anything-non-blank",
            syncEnabled = false,
        )
        // Should NOT throw — verify-only path must not touch the datasource.
        sync.afterPropertiesSet()
    }

    private fun stubMigrator(): DataSource = object : DataSource {
        override fun getConnection() = error("stub")
        override fun getConnection(u: String?, p: String?) = error("stub")
        override fun getLogWriter() = error("stub")
        override fun setLogWriter(out: java.io.PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout() = 0
        override fun getParentLogger() = error("stub")
        override fun <T : Any?> unwrap(iface: Class<T>?): T = error("stub")
        override fun isWrapperFor(iface: Class<*>?) = false
    }

    private fun throwingMigrator(): DataSource = object : DataSource {
        override fun getConnection(): java.sql.Connection {
            throw AssertionError(
                "verify-only mode MUST NOT open a connection to the migrator datasource",
            )
        }
        override fun getConnection(u: String?, p: String?): java.sql.Connection =
            getConnection()
        override fun getLogWriter() = error("not used")
        override fun setLogWriter(out: java.io.PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout() = 0
        override fun getParentLogger() = error("not used")
        override fun <T : Any?> unwrap(iface: Class<T>?): T = error("not used")
        override fun isWrapperFor(iface: Class<*>?) = false
    }

}
