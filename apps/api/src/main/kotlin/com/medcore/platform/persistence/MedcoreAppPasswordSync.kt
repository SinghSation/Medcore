package com.medcore.platform.persistence

import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor
import org.springframework.context.annotation.DependsOn
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Verifies (and, in local/test only, synchronises) the `medcore_app`
 * Postgres role's password before JPA opens its first connection.
 *
 * Two execution modes, controlled by `medcore.db.app.passwordSyncEnabled`:
 *
 *   - **`false` (production-default).** VERIFY-ONLY. Asserts the
 *     configured `medcore.db.app.password` value is non-blank and
 *     stops there. The application process does NOT call `ALTER
 *     ROLE`. The role's password is provisioned out-of-band by ops
 *     / the secret manager. The application therefore does not
 *     exercise role-rotation capability at runtime.
 *
 *   - **`true` (local / tests).** SYNC. After verification, runs
 *     `ALTER ROLE medcore_app WITH PASSWORD …` against the
 *     migrator datasource via a parameter-bound `pg_temp` function.
 *     Convenient for fresh-container test runs and for local
 *     iteration where the dev does not want to manually bootstrap
 *     the role's password.
 *
 * Residual scope (not addressed by this bean):
 *   The `@FlywayDataSource` is injected unconditionally, which
 *   means the application process holds migrator credentials in
 *   memory even when sync is disabled. Eliminating that residual
 *   requires moving Flyway out-of-process for production (separate
 *   ops slice — tracked as a carry-forward item). The behavior
 *   gate here addresses the immediate concern: the running
 *   application does not EXERCISE role-rotation capability in
 *   production, even though Flyway is in-process.
 *
 * The [JpaDependsOnPasswordCheck] post-processor below makes
 * EntityManagerFactory depend on this bean, so JPA's first
 * connection on the app datasource happens AFTER verification (and
 * sync, when enabled) completes.
 */
@Component("medcoreAppPasswordSync")
@DependsOn("flywayInitializer")
class MedcoreAppPasswordSync(
    @FlywayDataSource private val migratorDataSource: DataSource,
    @Value("\${medcore.db.app.password:}") private val appPassword: String,
    @Value("\${medcore.db.app.passwordSyncEnabled:false}") private val syncEnabled: Boolean,
) : InitializingBean {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterPropertiesSet() {
        require(appPassword.isNotBlank()) {
            "MEDCORE_DB_APP_PASSWORD must be set for the runtime role switch " +
                "(Phase 3E). The application connects as 'medcore_app' and uses " +
                "this value at connection time. In production the password is " +
                "provisioned out-of-band by ops / the secret manager — the " +
                "application does NOT alter the role itself. See " +
                "docs/runbooks/local-services.md §14."
        }

        if (!syncEnabled) {
            log.info(
                "MedcoreAppPasswordSync: VERIFY-only mode (production posture). " +
                    "Skipping ALTER ROLE; assuming medcore_app password is " +
                    "provisioned out-of-band.",
            )
            return
        }

        log.info(
            "MedcoreAppPasswordSync: SYNC mode (local/test). Aligning " +
                "medcore_app role password with configured value via the " +
                "migrator datasource.",
        )
        val jdbc = JdbcTemplate(migratorDataSource)
        // pg_temp function so the password is bound as a JDBC
        // parameter (`?`) and quoted via Postgres `format(... %L)` —
        // no Kotlin string interpolation of secret material.
        jdbc.execute(
            """
            CREATE OR REPLACE FUNCTION pg_temp.medcore_set_app_password(p_pwd TEXT)
            RETURNS VOID LANGUAGE plpgsql AS $$
            BEGIN
                EXECUTE format('ALTER ROLE medcore_app WITH PASSWORD %L', p_pwd);
            END;
            $$;
            """.trimIndent(),
        )
        jdbc.queryForObject(
            "SELECT pg_temp.medcore_set_app_password(?)",
            String::class.java,
            appPassword,
        )
    }
}

/**
 * Tells Spring Boot's JPA auto-config to create EntityManagerFactory
 * AFTER `medcoreAppPasswordSync` runs. JPA's first connection on
 * the application datasource therefore happens only after
 * verification (and sync, when enabled) completes.
 */
@Component
class JpaDependsOnPasswordCheck :
    EntityManagerFactoryDependsOnPostProcessor("medcoreAppPasswordSync")
