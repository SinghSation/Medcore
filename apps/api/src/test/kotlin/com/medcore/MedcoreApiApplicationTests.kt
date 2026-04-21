package com.medcore

import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MedcoreApiApplicationTests {

    @Autowired
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
    fun `flyway history records three successful baselines`() {
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
            ),
            rows,
            "Flyway history MUST contain the three baseline migrations in order, all successful",
        )
    }
}
