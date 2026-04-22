package com.medcore.platform.persistence

import javax.sql.DataSource
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Startup-time guarantee that the application's runtime datasource is
 * NOT connected as a Postgres superuser.
 *
 * Why this exists (ADR-001 §2 / Phase 3E):
 *   - RLS policies installed by V8 are bypassed by Postgres superusers
 *     unconditionally — `FORCE ROW LEVEL SECURITY` only binds table
 *     owners, not superusers, and `BYPASSRLS` is implicit on superusers.
 *   - The Phase 3E datasource split exists precisely so the runtime
 *     app connects as `medcore_app` (a non-superuser bound by RLS).
 *   - A misconfigured deployment that points the application datasource
 *     at the migrator role (or any superuser) silently turns RLS into a
 *     no-op for live request traffic. That is the single failure mode
 *     this check rejects loudly.
 *
 * Behaviour:
 *   - Listens for `ApplicationStartedEvent` (fires after the
 *     ApplicationContext is refreshed but before request handling
 *     begins under the embedded servlet).
 *   - Queries `current_user` and `pg_catalog.pg_roles.rolsuper` for
 *     the role the application is connected as.
 *   - If `rolsuper = true`, throws `IllegalStateException`. Spring
 *     Boot translates an exception thrown in a startup listener into a
 *     non-zero exit; the application does not begin serving traffic.
 *   - If the role exists and is not a superuser, the check passes
 *     silently. The role name is logged at `INFO` for operator
 *     visibility.
 *
 * Test escape hatch:
 *   - Tests that intentionally need to start the context with a
 *     superuser datasource (none exist today; flagged for a future
 *     slice if needed) can disable this check by excluding the bean
 *     from their `@SpringBootTest` configuration. There is no
 *     property-level disable: the guarantee is unconditional in
 *     production code.
 */
@Component
class DatabaseRoleSafetyCheck(
    private val dataSource: DataSource,
) {

    @EventListener(ApplicationStartedEvent::class)
    fun verifyNonSuperuserOnStartup() {
        val role = JdbcTemplate(dataSource).queryForObject(
            """
            SELECT current_user::text || '|' || COALESCE(rolsuper::text, 'false')
              FROM pg_catalog.pg_roles
             WHERE rolname = current_user
            """.trimIndent(),
            String::class.java,
        ) ?: error(
            "DatabaseRoleSafetyCheck could not identify the current DB role on startup. " +
                "Refusing to serve traffic.",
        )

        val parts = role.split("|", limit = 2)
        val user = parts[0]
        val isSuper = parts.getOrNull(1)?.equals("true", ignoreCase = true) == true

        if (isSuper) {
            error(
                "REFUSING TO START: application datasource is connected as superuser " +
                    "'$user'. RLS policies installed by V8 are bypassed by Postgres " +
                    "superusers unconditionally; the application MUST connect as a " +
                    "non-superuser role (typically 'medcore_app') before serving traffic. " +
                    "See ADR-001 §2 and the Phase 3E runbook (docs/runbooks/local-services.md " +
                    "§14). Check MEDCORE_DB_APP_USER / MEDCORE_DB_APP_PASSWORD configuration.",
            )
        }
    }
}
