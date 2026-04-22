package com.medcore.platform.persistence

import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor
import org.springframework.context.annotation.DependsOn
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ⚠️ **Deprecated as of Phase 3H.** Scheduled for removal in Phase
 * 3I when AWS Secrets Manager integration lands via Terraform.
 *
 * ### What changed in Phase 3H
 *
 * The VERIFY-only posture this class used to implement (Phase 3E)
 * is now handled by [SecretValidator]. That class reads every
 * required secret through [SecretSource] at `@PostConstruct` time,
 * fails context refresh on absence, and is the single fail-fast
 * entry point for missing secrets. No other caller should
 * duplicate that check.
 *
 * The SYNC path remains **only** for local-dev ergonomics — it
 * lets a dev clone the repo, `docker-compose up` Postgres, and run
 * `./gradlew flywayMigrate` + `./gradlew bootRun` without manually
 * invoking `ALTER ROLE medcore_app WITH PASSWORD ...`.
 *
 * ### Production-issuer guard
 *
 * Even with `medcore.db.app.passwordSyncEnabled=true`, SYNC refuses
 * to run if the configured OIDC issuer does not look local. This is
 * defence-in-depth against a misconfigured staging / prod
 * environment accidentally carrying the dev env var forward:
 *
 *   - `localhost` / `127.0.0.1` / `::1` → local
 *   - `mock-oauth2-server` → local / test
 *   - anything else → treated as non-local; SYNC refused
 *
 * Same logic pattern as `ProdProfileOidcGuard` (Phase 3A.3 /
 * ADR-002), deliberately shared so the "what counts as local" rule
 * lives in one place. Tracked in ADR-006 §6 as a behavioural
 * invariant for the SYNC path's remaining lifetime.
 *
 * ### Conditional activation
 *
 * `@ConditionalOnBean(name = ["flywayInitializer"])` — this class
 * only exists when Flyway runs in-process (i.e., `spring.flyway.enabled=true`).
 * Production deployments (Phase 3H onwards) set
 * `MEDCORE_APP_RUN_MIGRATIONS=false`, which disables Flyway at
 * startup, which removes `flywayInitializer`, which removes this
 * bean. No SYNC path exists in production.
 */
@Deprecated(
    message = "Scheduled for removal in Phase 3I. SYNC path retained " +
        "for local-dev ergonomics only; VERIFY path superseded by " +
        "SecretValidator. See ADR-006.",
    level = DeprecationLevel.WARNING,
)
@Component("medcoreAppPasswordSync")
@ConditionalOnProperty(
    value = ["spring.flyway.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@DependsOn("flywayInitializer")
class MedcoreAppPasswordSync(
    @FlywayDataSource private val migratorDataSource: DataSource,
    @Value("\${medcore.db.app.password:}") private val appPassword: String,
    @Value("\${medcore.db.app.passwordSyncEnabled:false}") private val syncEnabled: Boolean,
    @Value("\${medcore.oidc.issuer-uri:}") private val oidcIssuer: String,
) : InitializingBean {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterPropertiesSet() {
        if (!syncEnabled) {
            log.debug(
                "MedcoreAppPasswordSync: sync disabled. medcore_app password is " +
                    "assumed to be provisioned out-of-band by ops. Note: VERIFY " +
                    "path for presence is handled by SecretValidator.",
            )
            return
        }

        check(isLocalLikeIssuer(oidcIssuer)) {
            "REFUSING SYNC: medcore.db.app.passwordSyncEnabled=true but the " +
                "configured OIDC issuer (`$oidcIssuer`) does not match a " +
                "local-dev pattern (localhost / 127.0.0.1 / ::1 / " +
                "mock-oauth2-server). The SYNC path is local/test only " +
                "(ADR-006 §6). Set MEDCORE_DB_APP_PASSWORD_SYNC_ENABLED=false " +
                "and provision the medcore_app password out-of-band."
        }

        log.info(
            "MedcoreAppPasswordSync: SYNC mode (local/test). Aligning " +
                "medcore_app role password with configured value via the " +
                "migrator datasource.",
        )
        val jdbc = JdbcTemplate(migratorDataSource)
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

    /**
     * Returns true iff [issuer] looks like a local/test IdP. Matches
     * the same patterns ProdProfileOidcGuard refuses in production.
     * An empty issuer string is treated as local — dev setups that
     * haven't configured OIDC at all (unlikely, but test-friendly).
     */
    private fun isLocalLikeIssuer(issuer: String): Boolean {
        if (issuer.isBlank()) return true
        val lower = issuer.lowercase()
        return lower.contains("localhost") ||
            lower.contains("127.0.0.1") ||
            lower.contains("[::1]") ||
            lower.contains("mock-oauth2-server")
    }
}

/**
 * ⚠️ **Deprecated as of Phase 3H.** Retained only while
 * [MedcoreAppPasswordSync] exists; both are scheduled for removal
 * in Phase 3I.
 *
 * Makes `EntityManagerFactory` depend on `medcoreAppPasswordSync`
 * IFF that bean exists (local-dev with Flyway in-process). In
 * production (Flyway out-of-process, no `medcoreAppPasswordSync`
 * bean), this post-processor doesn't apply — the SecretValidator
 * fail-fast + FlywayMigrationStateCheck dependency chain handle
 * startup ordering.
 */
@Deprecated(
    message = "Retained while MedcoreAppPasswordSync exists. Removal in Phase 3I.",
    level = DeprecationLevel.WARNING,
)
@Component
@ConditionalOnProperty(
    value = ["spring.flyway.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class JpaDependsOnPasswordCheck :
    EntityManagerFactoryDependsOnPostProcessor("medcoreAppPasswordSync")
