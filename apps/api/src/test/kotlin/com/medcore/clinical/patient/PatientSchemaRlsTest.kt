package com.medcore.clinical.patient

import com.medcore.TestcontainersConfiguration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Proves V14's RLS policies on `clinical.patient` and
 * `clinical.patient_identifier` enforce tenant + membership
 * isolation even when the application authz layer is fully bypassed
 * (Phase 4A.1, ADR-007 §4.4 / 4A.1 design pack §3).
 *
 * The patient tables are Medcore's first PHI-bearing surface, so
 * the RLS contract is stricter than tenancy's:
 *
 *   1. **Both-GUCs requirement.** Policies key on BOTH
 *      `app.current_tenant_id` AND `app.current_user_id`. Missing
 *      either GUC fails closed (NULLIF(...)::uuid yields NULL;
 *      NULL comparisons return UNKNOWN; rows filter out).
 *   2. **Membership + role gates on writes.** Only OWNER and ADMIN
 *      memberships can INSERT / UPDATE / DELETE patients.
 *   3. **Soft-delete hides DELETED rows** from SELECT. MERGED_AWAY
 *      stays visible (merge-unwind workflow).
 *   4. **Identifier transitivity.** `patient_identifier` visibility
 *      follows the parent `patient` row via EXISTS subquery.
 *
 * Strategy: seed two tenants A/B + Alice (OWNER of A), Bob (OWNER
 * of B), Carol (SUSPENDED in A), Dave (MEMBER of A — can read but
 * not write), Eve (stranger, no memberships) via adminDataSource
 * (superuser, bypasses RLS). Then connect as `medcore_app`, set
 * GUCs, and assert Postgres enforces the invariant regardless of
 * what the app does.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PatientSchemaRlsTest {

    @Autowired
    @Qualifier("adminDataSource")
    lateinit var adminDataSource: DataSource

    @Autowired
    @Qualifier("appDataSource")
    lateinit var appDataSource: DataSource

    @Autowired
    lateinit var txManager: PlatformTransactionManager

    private val aliceId = UUID.randomUUID()
    private val bobId = UUID.randomUUID()
    private val carolId = UUID.randomUUID()
    private val daveId = UUID.randomUUID()
    private val eveId = UUID.randomUUID()
    private lateinit var tenantAId: UUID
    private lateinit var tenantBId: UUID
    private lateinit var patientAId: UUID
    private lateinit var patientBId: UUID
    private lateinit var patientADeletedId: UUID
    private lateinit var patientAMergedId: UUID
    private lateinit var identifierAId: UUID
    private lateinit var identifierBId: UUID

    @BeforeEach
    fun seed() {
        val adminJdbc = JdbcTemplate(adminDataSource)
        adminJdbc.update("DELETE FROM clinical.patient_mrn_counter")
        adminJdbc.update("DELETE FROM clinical.patient_identifier")
        adminJdbc.update("DELETE FROM clinical.patient")
        adminJdbc.update("DELETE FROM audit.audit_event")
        adminJdbc.update("DELETE FROM tenancy.tenant_membership")
        adminJdbc.update("DELETE FROM tenancy.tenant")
        adminJdbc.update("DELETE FROM identity.\"user\"")

        listOf(aliceId, bobId, carolId, daveId, eveId).forEach { uid ->
            adminJdbc.update(
                """
                INSERT INTO identity."user"(
                    id, issuer, subject, email_verified, status,
                    created_at, updated_at, row_version
                )
                VALUES (?, 'http://localhost/', ?::text, false, 'ACTIVE', NOW(), NOW(), 0)
                """.trimIndent(),
                uid, uid.toString(),
            )
        }

        tenantAId = UUID.randomUUID()
        tenantBId = UUID.randomUUID()
        adminJdbc.update(
            """
            INSERT INTO tenancy.tenant(
                id, slug, display_name, status, created_at, updated_at, row_version
            )
            VALUES
                (?, 'tenant-a', 'Tenant A', 'ACTIVE', NOW(), NOW(), 0),
                (?, 'tenant-b', 'Tenant B', 'ACTIVE', NOW(), NOW(), 0)
            """.trimIndent(),
            tenantAId, tenantBId,
        )
        // Alice: OWNER of A; Bob: OWNER of B; Carol: SUSPENDED in A;
        // Dave: MEMBER of A (read-only for patient); Eve: no memberships.
        adminJdbc.update(
            """
            INSERT INTO tenancy.tenant_membership(
                id, tenant_id, user_id, role, status,
                created_at, updated_at, row_version
            ) VALUES
                (?, ?, ?, 'OWNER',  'ACTIVE',    NOW(), NOW(), 0),
                (?, ?, ?, 'OWNER',  'ACTIVE',    NOW(), NOW(), 0),
                (?, ?, ?, 'OWNER',  'SUSPENDED', NOW(), NOW(), 0),
                (?, ?, ?, 'MEMBER', 'ACTIVE',    NOW(), NOW(), 0)
            """.trimIndent(),
            UUID.randomUUID(), tenantAId, aliceId,
            UUID.randomUUID(), tenantBId, bobId,
            UUID.randomUUID(), tenantAId, carolId,
            UUID.randomUUID(), tenantAId, daveId,
        )

        patientAId = UUID.randomUUID()
        patientBId = UUID.randomUUID()
        patientADeletedId = UUID.randomUUID()
        patientAMergedId = UUID.randomUUID()
        seedPatient(adminJdbc, patientAId, tenantAId, mrn = "A-0001", status = "ACTIVE", createdBy = aliceId)
        seedPatient(adminJdbc, patientBId, tenantBId, mrn = "B-0001", status = "ACTIVE", createdBy = bobId)
        seedPatient(
            adminJdbc, patientADeletedId, tenantAId,
            mrn = "A-DEL1", status = "DELETED", createdBy = aliceId,
        )
        // Merged-away row needs coherent merge fields (ck_clinical_patient_merged_fields_coherent).
        adminJdbc.update(
            """
            INSERT INTO clinical.patient(
                id, tenant_id, mrn, mrn_source,
                name_given, name_family, birth_date, administrative_sex,
                status, merged_into_id, merged_at, merged_by,
                created_at, updated_at, created_by, updated_by, row_version
            ) VALUES (
                ?, ?, ?, 'GENERATED',
                'Merged', 'Patient', DATE '1980-01-01', 'unknown',
                'MERGED_AWAY', ?, NOW(), ?,
                NOW(), NOW(), ?, ?, 0
            )
            """.trimIndent(),
            patientAMergedId, tenantAId, "A-MRG1",
            patientAId, aliceId,
            aliceId, aliceId,
        )

        identifierAId = UUID.randomUUID()
        identifierBId = UUID.randomUUID()
        seedIdentifier(adminJdbc, identifierAId, patientAId, value = "DL-A")
        seedIdentifier(adminJdbc, identifierBId, patientBId, value = "DL-B")
    }

    // ---- 1. Both-GUCs requirement — missing tenant GUC fails closed ----
    @Test
    fun `missing tenant GUC returns zero patient rows even with user GUC set`() {
        runAsAppWithGucs(userId = aliceId, tenantId = null) { jdbc ->
            val count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM clinical.patient",
                Int::class.java,
            )
            assertThat(count)
                .describedAs("missing app.current_tenant_id must fail closed")
                .isZero()
        }
    }

    // ---- 2. Both-GUCs requirement — missing user GUC fails closed ----
    @Test
    fun `missing user GUC returns zero patient rows even with tenant GUC set`() {
        runAsAppWithGucs(userId = null, tenantId = tenantAId) { jdbc ->
            val count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM clinical.patient",
                Int::class.java,
            )
            assertThat(count)
                .describedAs("missing app.current_user_id must fail closed")
                .isZero()
        }
    }

    // ---- 3. Cross-tenant isolation ----
    @Test
    fun `alice sees only tenant A patients — tenant B invisible`() {
        runAsAppWithGucs(userId = aliceId, tenantId = tenantAId) { jdbc ->
            val mrns = jdbc.query(
                "SELECT mrn FROM clinical.patient ORDER BY mrn",
                { rs, _ -> rs.getString("mrn") },
            )
            // A-0001 + A-MRG1 visible (ACTIVE + MERGED_AWAY);
            // A-DEL1 excluded by status filter; B-* excluded by tenant.
            assertThat(mrns).containsExactly("A-0001", "A-MRG1")
        }
    }

    // ---- 4. SUSPENDED membership reveals nothing ----
    @Test
    fun `carol with SUSPENDED membership sees zero patients in tenant A`() {
        runAsAppWithGucs(userId = carolId, tenantId = tenantAId) { jdbc ->
            val count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM clinical.patient",
                Int::class.java,
            )
            assertThat(count).isZero()
        }
    }

    // ---- 5. DELETED status excluded from SELECT ----
    @Test
    fun `DELETED patient is invisible to owner via SELECT`() {
        runAsAppWithGucs(userId = aliceId, tenantId = tenantAId) { jdbc ->
            val seen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM clinical.patient WHERE id = ?",
                Int::class.java,
                patientADeletedId,
            )
            assertThat(seen)
                .describedAs("DELETED rows must be filtered by RLS SELECT policy")
                .isZero()
        }
    }

    // ---- 6. Non-member sees nothing ----
    @Test
    fun `eve with no memberships sees zero patients anywhere`() {
        runAsAppWithGucs(userId = eveId, tenantId = tenantAId) { jdbc ->
            val count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM clinical.patient",
                Int::class.java,
            )
            assertThat(count).isZero()
        }
        runAsAppWithGucs(userId = eveId, tenantId = tenantBId) { jdbc ->
            val count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM clinical.patient",
                Int::class.java,
            )
            assertThat(count).isZero()
        }
    }

    // ---- 7. Identifier transitivity — identifiers follow parent's RLS ----
    @Test
    fun `patient_identifier visibility follows parent patient row`() {
        // Alice sees A's identifier, not B's.
        runAsAppWithGucs(userId = aliceId, tenantId = tenantAId) { jdbc ->
            val values = jdbc.query(
                "SELECT value FROM clinical.patient_identifier ORDER BY value",
                { rs, _ -> rs.getString("value") },
            )
            assertThat(values).containsExactly("DL-A")
        }
        // Bob sees B's identifier, not A's.
        runAsAppWithGucs(userId = bobId, tenantId = tenantBId) { jdbc ->
            val values = jdbc.query(
                "SELECT value FROM clinical.patient_identifier ORDER BY value",
                { rs, _ -> rs.getString("value") },
            )
            assertThat(values).containsExactly("DL-B")
        }
    }

    // ---- 8. OWNER/ADMIN can INSERT, MEMBER cannot ----
    @Test
    fun `OWNER alice CAN INSERT a patient into tenant A`() {
        val newId = UUID.randomUUID()
        val rows = runAsAppWithGucs(userId = aliceId, tenantId = tenantAId) { jdbc ->
            jdbc.update(
                """
                INSERT INTO clinical.patient(
                    id, tenant_id, mrn, mrn_source,
                    name_given, name_family, birth_date, administrative_sex,
                    status, created_at, updated_at, created_by, updated_by, row_version
                ) VALUES (
                    ?, ?, 'A-0002', 'GENERATED',
                    'New', 'Patient', DATE '1990-05-15', 'female',
                    'ACTIVE', NOW(), NOW(), ?, ?, 0
                )
                """.trimIndent(),
                newId, tenantAId, aliceId, aliceId,
            )
        }
        assertThat(rows).isEqualTo(1)
    }

    // ---- 9. MEMBER cannot INSERT patient (no OWNER/ADMIN role) ----
    @Test
    fun `MEMBER dave CANNOT INSERT a patient — WITH CHECK violation`() {
        val newId = UUID.randomUUID()
        runAsAppWithGucs(userId = daveId, tenantId = tenantAId) { jdbc ->
            assertThatThrownBy {
                jdbc.update(
                    """
                    INSERT INTO clinical.patient(
                        id, tenant_id, mrn, mrn_source,
                        name_given, name_family, birth_date, administrative_sex,
                        status, created_at, updated_at, created_by, updated_by, row_version
                    ) VALUES (
                        ?, ?, 'A-MBR1', 'GENERATED',
                        'Member', 'Write', DATE '1970-01-01', 'unknown',
                        'ACTIVE', NOW(), NOW(), ?, ?, 0
                    )
                    """.trimIndent(),
                    newId, tenantAId, daveId, daveId,
                )
            }.isInstanceOf(Exception::class.java) // RLS WITH CHECK refuses
        }
    }

    // ---- 10. Cross-tenant UPDATE silently zero rows ----
    @Test
    fun `alice CANNOT UPDATE a tenant B patient — USING clause hides the row`() {
        val rows = runAsAppWithGucs(userId = aliceId, tenantId = tenantAId) { jdbc ->
            // Even if alice guesses B's patient id, the UPDATE's USING
            // clause evaluates false (tenant mismatch) and Postgres
            // silently returns zero rows.
            jdbc.update(
                "UPDATE clinical.patient SET name_family = 'Hijacked' WHERE id = ?",
                patientBId,
            )
        }
        assertThat(rows)
            .describedAs("cross-tenant UPDATE must affect zero rows")
            .isEqualTo(0)
    }

    // ---- V17 (Phase 4A.3) — identifier write policies now require OWNER/ADMIN ----
    //
    // V14 shipped identifier write policies that delegated only to
    // parent-patient visibility (ACTIVE membership), NOT role. V17
    // tightens to match V14's patient-write role gate. The four
    // tests below prove the tightened policies.

    @Test
    fun `V17 — OWNER alice CAN INSERT a patient_identifier (happy path)`() {
        val newId = UUID.randomUUID()
        val rows = runAsAppWithGucs(userId = aliceId, tenantId = tenantAId) { jdbc ->
            jdbc.update(
                """
                INSERT INTO clinical.patient_identifier(
                    id, patient_id, type, issuer, value,
                    created_at, updated_at, row_version
                ) VALUES (?, ?, 'DRIVERS_LICENSE', 'CA', 'A-OWN-1', NOW(), NOW(), 0)
                """.trimIndent(),
                newId, patientAId,
            )
        }
        assertThat(rows).isEqualTo(1)
    }

    @Test
    fun `V17 — MEMBER dave CANNOT INSERT a patient_identifier — role-gated WITH CHECK refusal`() {
        runAsAppWithGucs(userId = daveId, tenantId = tenantAId) { jdbc ->
            assertThatThrownBy {
                jdbc.update(
                    """
                    INSERT INTO clinical.patient_identifier(
                        id, patient_id, type, issuer, value,
                        created_at, updated_at, row_version
                    ) VALUES (?, ?, 'DRIVERS_LICENSE', 'CA', 'A-MBR-1', NOW(), NOW(), 0)
                    """.trimIndent(),
                    UUID.randomUUID(), patientAId,
                )
            }.isInstanceOf(Exception::class.java) // V17 WITH CHECK refusal
        }
    }

    @Test
    fun `V17 — MEMBER dave CANNOT UPDATE a patient_identifier — role-gated USING refusal`() {
        // Admin path seeded an identifier (identifierAId) attached to
        // patient A. Dave as MEMBER tries to soft-delete it via
        // valid_to (the operational revoke path). V17's UPDATE
        // policy USING clause hides the row from the MEMBER caller
        // → zero rows affected.
        val rows = runAsAppWithGucs(userId = daveId, tenantId = tenantAId) { jdbc ->
            jdbc.update(
                "UPDATE clinical.patient_identifier SET valid_to = NOW() WHERE id = ?",
                identifierAId,
            )
        }
        assertThat(rows)
            .describedAs("V17 — MEMBER role must not update identifier rows")
            .isZero()
    }

    @Test
    fun `V17 — MEMBER dave CANNOT DELETE a patient_identifier — role-gated USING refusal`() {
        val rows = runAsAppWithGucs(userId = daveId, tenantId = tenantAId) { jdbc ->
            jdbc.update(
                "DELETE FROM clinical.patient_identifier WHERE id = ?",
                identifierAId,
            )
        }
        assertThat(rows)
            .describedAs("V17 — MEMBER role must not delete identifier rows")
            .isZero()
    }

    // ---- helpers ----

    private fun seedPatient(
        jdbc: JdbcTemplate,
        id: UUID,
        tenantId: UUID,
        mrn: String,
        status: String,
        createdBy: UUID,
    ) {
        jdbc.update(
            """
            INSERT INTO clinical.patient(
                id, tenant_id, mrn, mrn_source,
                name_given, name_family, birth_date, administrative_sex,
                status, created_at, updated_at, created_by, updated_by, row_version
            ) VALUES (
                ?, ?, ?, 'GENERATED',
                'First', 'Last', DATE '1975-06-01', 'male',
                ?, NOW(), NOW(), ?, ?, 0
            )
            """.trimIndent(),
            id, tenantId, mrn, status, createdBy, createdBy,
        )
    }

    private fun seedIdentifier(
        jdbc: JdbcTemplate,
        id: UUID,
        patientId: UUID,
        value: String,
    ) {
        jdbc.update(
            """
            INSERT INTO clinical.patient_identifier(
                id, patient_id, type, issuer, value,
                created_at, updated_at, row_version
            ) VALUES (
                ?, ?, 'DRIVERS_LICENSE', 'CA', ?,
                NOW(), NOW(), 0
            )
            """.trimIndent(),
            id, patientId, value,
        )
    }

    private fun <R> runAsAppWithGucs(
        userId: UUID?,
        tenantId: UUID?,
        action: (JdbcTemplate) -> R,
    ): R {
        val jdbc = JdbcTemplate(appDataSource)
        val template = TransactionTemplate(txManager)
        return template.execute {
            jdbc.queryForObject(
                "SELECT set_config('app.current_user_id', ?, true)",
                String::class.java,
                userId?.toString().orEmpty(),
            )
            jdbc.queryForObject(
                "SELECT set_config('app.current_tenant_id', ?, true)",
                String::class.java,
                tenantId?.toString().orEmpty(),
            )
            action(jdbc)
        }!!
    }

    @Suppress("unused") // Reserved for future timestamp-shaped seed rows.
    private fun now(): Instant = Instant.now()
}
