package com.medcore

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import no.nav.security.mock.oauth2.MockOAuth2Server
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Shared test infrastructure for Phase 3E and beyond.
 *
 * Datasource model:
 *   - **Primary application datasource** (`@Primary`) connects as the
 *     non-superuser role `medcore_app` so RLS policies installed by
 *     V8 actually enforce on every test that goes through the
 *     application layer. This mirrors the runtime posture
 *     established by Phase 3E.
 *   - **Flyway datasource** (`@FlywayDataSource`) connects as the
 *     container superuser — the test-bed migrator role. This is what
 *     runs DDL, including V10 which sets `medcore_app`'s password
 *     via the `app_password` Flyway placeholder.
 *   - **Admin datasource** (qualified `adminDataSource`) is a third
 *     superuser DataSource that test code uses for cleanup, seeding,
 *     and tampering paths the application role cannot perform
 *     (DELETE on audit_event, INSERT on tenancy.tenant, ALTER ROLE,
 *     etc.). Tests autowire it explicitly with the qualifier; the
 *     `@Primary` (medcore_app) datasource is what unqualified
 *     autowires resolve to.
 *
 * Container is started eagerly so its `jdbcUrl` / `username` /
 * `password` are available to every DataSource bean below.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    fun postgresContainer(): PostgreSQLContainer<*> {
        val container = PostgreSQLContainer("postgres:16.4")
        container.start()
        return container
    }

    @Bean(destroyMethod = "shutdown")
    fun mockOAuth2Server(): MockOAuth2Server {
        val server = MockOAuth2Server()
        server.start()
        return server
    }

    /**
     * The primary application datasource — `medcore_app`. JPA's
     * EntityManagerFactory and any unqualified `@Autowired DataSource`
     * resolve here. The role's password is set by V10 (Flyway) before
     * this DataSource opens its first connection.
     */
    @Bean
    @Primary
    fun appDataSource(postgres: PostgreSQLContainer<*>): DataSource =
        HikariDataSource().apply {
            jdbcUrl = postgres.jdbcUrl
            driverClassName = "org.postgresql.Driver"
            username = APP_ROLE
            password = APP_ROLE_PASSWORD
            poolName = "medcore-test-app"
            maximumPoolSize = 5
            minimumIdle = 1
        }

    /**
     * Migrator / Flyway datasource — container superuser. Annotated
     * `@FlywayDataSource` so Spring Boot's Flyway autoconfig picks it
     * up instead of the primary datasource.
     */
    @Bean
    @FlywayDataSource
    fun flywayDataSource(postgres: PostgreSQLContainer<*>): DataSource =
        HikariDataSource().apply {
            jdbcUrl = postgres.jdbcUrl
            driverClassName = "org.postgresql.Driver"
            username = postgres.username
            password = postgres.password
            poolName = "medcore-test-flyway"
            maximumPoolSize = 2
            minimumIdle = 0
        }

    /**
     * Superuser DataSource for test fixtures. Tests autowire with the
     * "adminDataSource" qualifier; never resolves under unqualified
     * autowire (so accidental autowires hit the least-privileged
     * primary datasource and surface the misuse loudly).
     */
    @Bean(name = ["adminDataSource"])
    fun adminDataSource(postgres: PostgreSQLContainer<*>): DataSource =
        DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }

    @Bean
    fun dynamicPropertyRegistrar(
        server: MockOAuth2Server,
    ): DynamicPropertyRegistrar = DynamicPropertyRegistrar { registry ->
        // Same value MedcoreAppPasswordSync writes into the role and
        // the appDataSource bean uses to connect.
        registry.add("medcore.db.app.password") { APP_ROLE_PASSWORD }
        registry.add("medcore.oidc.issuer-uri") {
            server.issuerUrl(MOCK_ISSUER_ID).toString()
        }
    }

    companion object {
        const val MOCK_ISSUER_ID: String = "medcore-test"
        const val APP_ROLE: String = "medcore_app"
        const val APP_ROLE_PASSWORD: String = "medcore_app_testcontainers_only"

        init {
            // Set both properties at JVM class-load time so they are
            // visible to every Spring context the test suite creates —
            // DynamicPropertyRegistrar can lose to other customisers in
            // some web environments, but `System.setProperty` lands in
            // SystemEnvironmentPropertySource at high precedence.
            //
            //   - `medcore.db.app.password`: local-only password,
            //     scoped to the ephemeral Testcontainers Postgres.
            //   - `medcore.db.app.password-sync.enabled=true`: tests
            //     opt INTO the password-sync component (which calls
            //     ALTER ROLE against the migrator datasource on every
            //     startup) because each Testcontainers run starts with
            //     a fresh role that has no password yet. Production
            //     stays opt-OUT (default) so the runtime app process
            //     never carries role-rotation capability.
            System.setProperty("medcore.db.app.password", APP_ROLE_PASSWORD)
            System.setProperty("medcore.db.app.passwordSyncEnabled", "true")
        }
    }
}
