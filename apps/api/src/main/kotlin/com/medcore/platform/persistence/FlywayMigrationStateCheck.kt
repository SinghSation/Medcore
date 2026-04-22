package com.medcore.platform.persistence

import jakarta.annotation.PostConstruct
import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Fail-fast assertion that the database schema has been migrated up
 * to (or beyond) the version this application binary expects.
 *
 * Phase 3H moves Flyway out of the application process for production
 * deployments (see ADR-006). With `spring.flyway.enabled=false`,
 * nothing at application startup runs migrations — the deployment
 * pipeline runs the Flyway CLI container ahead of the application
 * task. If that pipeline step is skipped / fails / is out of order,
 * the application would otherwise start with a stale schema; its
 * Hibernate `ddl-auto=validate` would eventually fail with a generic
 * "column not found" error.
 *
 * This check runs in `@PostConstruct`, BEFORE
 * [jakarta.persistence.EntityManagerFactory] initialisation (see
 * [JpaDependsOnFlywayCheck] below), and produces a specific,
 * actionable error message:
 *
 *     REFUSING TO START: latest applied migration rank (N) is below
 *     the expected minimum (M). Run Flyway migrations before
 *     starting the application. See docs/runbooks/secrets-and-migrations.md
 *     §Deployment sequence.
 *
 * The check fires BEFORE `ApplicationStartedEvent` and BEFORE
 * `ApplicationReadyEvent`, so the application never becomes "ready"
 * with a stale schema — readiness probes stay DOWN and the deploy
 * pipeline's rolling-update logic correctly refuses to route
 * traffic.
 *
 * Version strategy: [MIN_EXPECTED_INSTALLED_RANK] is a compile-time
 * constant equal to the latest migration version baked into this
 * build. Adding a new migration means bumping this constant — a
 * forcing function to keep the check honest.
 *
 * Why `installed_rank` and not checksum: per ADR-006 §rationale, a
 * checksum comparison fails on harmless migration edits (comment
 * changes, formatting) while still letting missing migrations
 * through unchecked. Version-monotonicity is the invariant that
 * matters.
 */
/**
 * Conditional on `spring.flyway.enabled=false` — the check is only
 * meaningful when Flyway runs OUT of the application process (prod
 * deployments per ADR-006). When Flyway runs in-process
 * (default; dev + test), Flyway's own `validateOnMigrate=true` is
 * the equivalent guard and this bean would race with
 * `flywayInitializer` at @PostConstruct time. Not worth a complex
 * ordering dance; the property condition sidesteps it entirely.
 */
@Component
@ConditionalOnProperty(
    value = ["spring.flyway.enabled"],
    havingValue = "false",
)
class FlywayMigrationStateCheck(private val dataSource: DataSource) {

    private val log = LoggerFactory.getLogger(FlywayMigrationStateCheck::class.java)

    @PostConstruct
    fun check() {
        val jdbc = JdbcTemplate(dataSource)
        val latestRank = try {
            jdbc.queryForObject(
                "SELECT COALESCE(MAX(installed_rank), 0) FROM flyway.flyway_schema_history",
                Int::class.java,
            ) ?: 0
        } catch (ex: Exception) {
            // Absent flyway_schema_history → migrations have never
            // run. Treat the same as "below expected minimum".
            log.error(
                "flyway.flyway_schema_history unreadable — migrations may not have run",
                ex,
            )
            0
        }

        check(latestRank >= MIN_EXPECTED_INSTALLED_RANK) {
            "REFUSING TO START: latest applied migration rank " +
                "($latestRank) is below the expected minimum " +
                "($MIN_EXPECTED_INSTALLED_RANK). Run Flyway migrations " +
                "before starting the application. See " +
                "docs/runbooks/secrets-and-migrations.md §Deployment sequence."
        }
        log.info(
            "[FLYWAY] FlywayMigrationStateCheck: schema at rank {} (expected >= {})",
            latestRank,
            MIN_EXPECTED_INSTALLED_RANK,
        )
    }

    companion object {
        /**
         * Compile-time constant equal to the highest `installed_rank`
         * Flyway's `flyway_schema_history` will show after applying
         * every migration baked into this build's resources.
         *
         * Migration IDs in the `db/migration` tree:
         *   V1..V12 at time of Phase 3J (V12 added RLS write
         *   policies + bootstrap function). After V12 applies, the
         *   history table's `installed_rank` reaches 12.
         *
         * Bump this constant whenever a new VN migration lands (the
         * `safe-db-migration` skill in `.claude/skills/` carries this
         * as step N of the migration checklist).
         */
        const val MIN_EXPECTED_INSTALLED_RANK: Int = 12
    }
}

/**
 * Makes Spring Boot's auto-configured JPA `EntityManagerFactory`
 * depend on [FlywayMigrationStateCheck], so Hibernate's
 * schema-validation cannot run against a stale schema.
 *
 * Without this post-processor, `EntityManagerFactory` and
 * `FlywayMigrationStateCheck` would be siblings depending on the
 * same `DataSource`, and Spring's bean-initialisation order between
 * them is undefined. Explicit `@DependsOn` via this post-processor
 * forces the order.
 */
@Component
@ConditionalOnProperty(
    value = ["spring.flyway.enabled"],
    havingValue = "false",
)
class JpaDependsOnFlywayCheck :
    EntityManagerFactoryDependsOnPostProcessor("flywayMigrationStateCheck")
