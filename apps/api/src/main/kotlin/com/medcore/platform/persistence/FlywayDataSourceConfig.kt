package com.medcore.platform.persistence

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Production-side counterpart to the two-datasource bean topology
 * defined in `TestcontainersConfiguration`. Declares both:
 *
 *   - **`@Primary`** application DataSource — connects as `medcore_app`.
 *     This is what JPA's `EntityManagerFactory` and every
 *     unqualified `@Autowired DataSource` resolve to.
 *   - **`@FlywayDataSource`** migrator DataSource — connects as the
 *     superuser / migrator role. Used by Spring Boot's Flyway
 *     autoconfig and by `MedcoreAppPasswordSync` (Phase 3E).
 *
 * Declaring any DataSource bean here disables Spring Boot's default
 * `DataSourceAutoConfiguration` (it's `@ConditionalOnMissingBean`),
 * so both must be declared explicitly. Omitting the `@Primary` one
 * caused `DatabaseRoleSafetyCheck` to refuse startup because JPA
 * fell back to the Flyway (superuser) pool.
 *
 * Guarded by `spring.flyway.enabled=true` (the default for dev/test)
 * so production deployments running Flyway out-of-process
 * (`MEDCORE_APP_RUN_MIGRATIONS=false`, ADR-006 §4) keep Spring Boot's
 * default single-DataSource behaviour.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    value = ["spring.flyway.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@ConditionalOnMissingBean(DataSource::class)
class FlywayDataSourceConfig(
    @Value("\${spring.datasource.url}") private val appUrl: String,
    @Value("\${spring.datasource.username}") private val appUser: String,
    @Value("\${spring.datasource.password:}") private val appPassword: String,
    @Value("\${spring.flyway.url}") private val flywayUrl: String,
    @Value("\${spring.flyway.user}") private val flywayUser: String,
    @Value("\${spring.flyway.password:}") private val flywayPassword: String,
) {

    @Bean(destroyMethod = "close")
    @Primary
    fun appDataSource(): DataSource = HikariDataSource().apply {
        jdbcUrl = appUrl
        driverClassName = "org.postgresql.Driver"
        username = appUser
        password = appPassword
        poolName = "medcore-hikari"
        maximumPoolSize = 10
        minimumIdle = 2
    }

    @Bean(destroyMethod = "close")
    @FlywayDataSource
    fun flywayDataSource(): DataSource = HikariDataSource().apply {
        jdbcUrl = flywayUrl
        driverClassName = "org.postgresql.Driver"
        username = flywayUser
        password = flywayPassword
        poolName = "medcore-flyway"
        maximumPoolSize = 2
        minimumIdle = 0
    }
}
