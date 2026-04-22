package com.medcore.platform.persistence

import com.medcore.TestcontainersConfiguration
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Enforces the Phase 3H invariant that every application table
 * (tables under the `identity`, `tenancy`, and `audit` schemas)
 * has the appropriate `medcore_app` privilege set applied via
 * migration.
 *
 * Closes the last residual risk flagged in the 3H final pressure
 * test: "one missed GRANT in a future migration = silent
 * production outage." Every future slice that adds a new
 * application table MUST either:
 *
 *   - GRANT the needed privileges to `medcore_app` in the same
 *     migration that creates the table, OR
 *   - Explicitly opt the table out of this test by adding it to
 *     [TABLES_EXCLUDED_FROM_READ_CHECK] with a one-line comment
 *     explaining the exemption (only used for tables that
 *     belong to the migrator alone — flyway_schema_history is
 *     NOT an application table).
 *
 * The check runs against the live Testcontainers DB after all
 * migrations have applied. It does not parse migration SQL —
 * that approach would miss grants applied via `ALTER DEFAULT
 * PRIVILEGES` or cumulative grants across multiple migrations.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AppRoleGrantsTest {

    @Autowired
    @Qualifier("adminDataSource")
    lateinit var adminDataSource: DataSource

    @Test
    fun `medcore_app has SELECT on every application table`() {
        val jdbc = JdbcTemplate(adminDataSource)
        val tables = jdbc.queryForList(
            """
            SELECT table_schema || '.' || quote_ident(table_name) AS qname
              FROM information_schema.tables
             WHERE table_schema IN ('identity', 'tenancy', 'audit')
               AND table_type = 'BASE TABLE'
            """.trimIndent(),
            String::class.java,
        )

        assertThat(tables)
            .describedAs("at least one application table must exist post-migration")
            .isNotEmpty()

        val missingSelect = tables.filter { qname ->
            if (qname in TABLES_EXCLUDED_FROM_READ_CHECK) return@filter false
            val hasSelect = jdbc.queryForObject(
                "SELECT has_table_privilege('medcore_app', ?, 'SELECT')",
                Boolean::class.java,
                qname,
            ) ?: false
            !hasSelect
        }

        assertThat(missingSelect)
            .describedAs(
                "Tables missing SELECT privilege for medcore_app. Every application " +
                    "table MUST grant medcore_app the privileges required by its " +
                    "access pattern — SELECT at minimum. The migration that creates " +
                    "the table must also GRANT, or a follow-up migration must add " +
                    "the GRANT before shipping a caller. See ADR-006 §2.3."
            )
            .isEmpty()
    }

    @Test
    fun `medcore_app has zero BYPASSRLS or superuser attributes`() {
        // Paired invariant: the app role must not have been
        // accidentally granted BYPASSRLS via a future migration,
        // which would defeat Phase 3E's RLS enforcement. Cheap
        // check to run alongside the grants test so a regression
        // surfaces in exactly one place.
        val jdbc = JdbcTemplate(adminDataSource)
        val row = jdbc.queryForMap(
            "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = 'medcore_app'",
        )
        assertThat(row["rolsuper"]).describedAs("medcore_app must NOT be superuser").isEqualTo(false)
        assertThat(row["rolbypassrls"]).describedAs("medcore_app must NOT bypass RLS").isEqualTo(false)
    }

    companion object {
        /**
         * Fully-qualified, `quote_ident`-normalised names of any
         * tables deliberately excluded from the `medcore_app` read
         * requirement. Empty at Phase 3H — every application table
         * currently requires a readable-to-app grant. Additions here
         * are a Tier 3 edit and MUST carry an inline justification.
         */
        val TABLES_EXCLUDED_FROM_READ_CHECK: Set<String> = emptySet()
    }
}
