package com.medcore

import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MedcoreApiApplicationTests {

    // Use the admin datasource: medcore_app does not have SELECT on
    // flyway.flyway_schema_history; the migrator/superuser does.
    @Autowired
    @Qualifier("adminDataSource")
    lateinit var dataSource: DataSource

    @Test
    fun `spring context loads`() {
        // Boots the full application context against a Testcontainers Postgres,
        // runs Flyway, and validates JPA. Implicit coverage; no assertion here.
    }

    @Test
    fun `flyway provisions the four expected schemas`() {
        val expected = setOf("flyway", "identity", "tenancy", "audit")
        val actual = mutableSetOf<String>()
        dataSource.connection.use { conn ->
            conn.createStatement().executeQuery(
                "SELECT schema_name FROM information_schema.schemata"
            ).use { rs ->
                while (rs.next()) actual += rs.getString(1)
            }
        }
        assertTrue(
            actual.containsAll(expected),
            "expected schemas $expected to be present; actual = $actual",
        )
    }

    @Test
    fun `flyway history records expected migrations in order`() {
        data class Row(val version: String, val script: String, val success: Boolean)

        val rows = mutableListOf<Row>()
        dataSource.connection.use { conn ->
            conn.createStatement().executeQuery(
                """
                SELECT version, script, success
                  FROM flyway.flyway_schema_history
                 WHERE version IS NOT NULL
                 ORDER BY installed_rank
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) {
                    rows += Row(
                        version = rs.getString("version"),
                        script = rs.getString("script"),
                        success = rs.getBoolean("success"),
                    )
                }
            }
        }

        assertEquals(
            listOf(
                Row("1", "V1__identity_baseline.sql", true),
                Row("2", "V2__tenancy_baseline.sql", true),
                Row("3", "V3__audit_baseline.sql", true),
                Row("4", "V4__identity_user.sql", true),
                Row("5", "V5__tenant.sql", true),
                Row("6", "V6__tenant_membership.sql", true),
                Row("7", "V7__audit_event.sql", true),
                Row("8", "V8__tenancy_rls.sql", true),
                Row("9", "V9__audit_event_v2.sql", true),
                Row("10", "V10__runtime_role_grants.sql", true),
                Row("11", "V11__medcore_migrator_role.sql", true),
            ),
            rows,
            "Flyway history MUST contain every shipped migration in order, all successful",
        )
    }
}
