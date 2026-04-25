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
    fun `flyway provisions the five expected schemas`() {
        val expected = setOf("flyway", "identity", "tenancy", "audit", "clinical")
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
                Row("12", "V12__tenancy_rls_write_policies.sql", true),
                Row("13", "V13__tenancy_membership_rls_admin_read.sql", true),
                Row("14", "V14__clinical_patient_schema.sql", true),
                Row("15", "V15__patient_mrn_counter.sql", true),
                Row("16", "V16__fuzzystrmatch_public_schema.sql", true),
                Row("17", "V17__patient_identifier_role_gate.sql", true),
                Row("18", "V18__clinical_encounter.sql", true),
                Row("19", "V19__clinical_encounter_note.sql", true),
                Row("20", "V20__clinical_encounter_note_signing.sql", true),
                Row("21", "V21__clinical_encounter_lifecycle.sql", true),
                Row("22", "V22__clinical_encounter_one_in_progress_per_patient.sql", true),
                Row("23", "V23__clinical_encounter_note_amendment_integrity.sql", true),
                Row("24", "V24__clinical_allergy.sql", true),
                Row("25", "V25__clinical_problem.sql", true),
                Row("26", "V26__encounter_note_pagination_index.sql", true),
                Row("27", "V27__encounter_pagination_index.sql", true),
                Row("28", "V28__allergy_pagination_index.sql", true),
            ),
            rows,
            "Flyway history MUST contain every shipped migration in order, all successful",
        )
    }
}
