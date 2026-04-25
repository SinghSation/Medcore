package com.medcore.clinical.patient

import com.medcore.TestcontainersConfiguration
import com.medcore.clinical.patient.model.AdministrativeSex
import com.medcore.clinical.patient.model.MrnSource
import com.medcore.clinical.patient.model.PatientIdentifierType
import com.medcore.clinical.patient.model.PatientStatus
import com.medcore.clinical.patient.persistence.PatientEntity
import com.medcore.clinical.patient.persistence.PatientIdentifierEntity
import com.medcore.clinical.patient.persistence.PatientIdentifierRepository
import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.platform.persistence.TenancySessionContext
import java.time.Instant
import java.time.LocalDate
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
 * Verifies the JPA mapping for [PatientEntity] + [PatientIdentifierEntity]
 * against the V14 schema (Phase 4A.1).
 *
 * Two concerns:
 *
 *   1. **Roundtrip**: save + flush + findById returns an entity
 *      with every field preserved (including the FHIR-wire
 *      administrative_sex pass-through and the `@Version`
 *      row_version bump).
 *   2. **Database-level constraints**: the CHECK + UNIQUE
 *      constraints declared in V14 actually fire — validation in
 *      SQL, not just in Kotlin.
 *
 * Roundtrip goes through [PatientRepository] (the `medcore_app`
 * primary datasource) with BOTH RLS GUCs set via
 * [TenancySessionContext], matching the runtime pattern where
 * `PhiRlsTxHook` establishes GUCs before the handler's JPA
 * save() runs.
 *
 * Constraint violations bypass RLS via the `adminDataSource`
 * (superuser, BYPASSRLS) so the test isolates the database check
 * from the policy gate — the concerns are orthogonal and the
 * RLS test already proves the policy side.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PatientEntityMappingTest {

    @Autowired
    lateinit var patientRepository: PatientRepository

    @Autowired
    lateinit var patientIdentifierRepository: PatientIdentifierRepository

    @Autowired
    lateinit var tenancySessionContext: TenancySessionContext

    @Autowired
    lateinit var txManager: PlatformTransactionManager

    @Autowired
    @Qualifier("adminDataSource")
    lateinit var adminDataSource: DataSource

    private val aliceId = UUID.randomUUID()
    private lateinit var tenantId: UUID

    @BeforeEach
    fun seed() {
        val admin = JdbcTemplate(adminDataSource)
        admin.update("DELETE FROM clinical.patient_mrn_counter")
        admin.update("DELETE FROM clinical.problem")
        admin.update("DELETE FROM clinical.allergy")
        admin.update("DELETE FROM clinical.patient_identifier")
        admin.update("DELETE FROM clinical.patient")
        admin.update("DELETE FROM audit.audit_event")
        admin.update("DELETE FROM tenancy.tenant_membership")
        admin.update("DELETE FROM tenancy.tenant")
        admin.update("DELETE FROM identity.\"user\"")

        admin.update(
            """
            INSERT INTO identity."user"(
                id, issuer, subject, email_verified, status,
                created_at, updated_at, row_version
            ) VALUES (?, 'http://localhost/', ?::text, false, 'ACTIVE', NOW(), NOW(), 0)
            """.trimIndent(),
            aliceId, aliceId.toString(),
        )
        tenantId = UUID.randomUUID()
        admin.update(
            """
            INSERT INTO tenancy.tenant(
                id, slug, display_name, status, created_at, updated_at, row_version
            ) VALUES (?, 'map-tenant', 'Mapping Tenant', 'ACTIVE', NOW(), NOW(), 0)
            """.trimIndent(),
            tenantId,
        )
        admin.update(
            """
            INSERT INTO tenancy.tenant_membership(
                id, tenant_id, user_id, role, status,
                created_at, updated_at, row_version
            ) VALUES (?, ?, ?, 'OWNER', 'ACTIVE', NOW(), NOW(), 0)
            """.trimIndent(),
            UUID.randomUUID(), tenantId, aliceId,
        )
    }

    @Test
    fun `save and reload preserves every column including administrative_sex wire value`() {
        val id = UUID.randomUUID()
        val now = Instant.parse("2026-01-15T10:00:00Z")
        val entity = PatientEntity(
            id = id,
            tenantId = tenantId,
            mrn = "MAP-0001",
            mrnSource = MrnSource.GENERATED,
            nameGiven = "Ada",
            nameFamily = "Lovelace",
            nameMiddle = "Augusta",
            nameSuffix = null,
            namePrefix = "Ms.",
            preferredName = "Ada",
            birthDate = LocalDate.of(1815, 12, 10),
            administrativeSexWire = AdministrativeSex.FEMALE.wireValue,
            sexAssignedAtBirth = "F",
            genderIdentityCode = null,
            preferredLanguage = "en",
            status = PatientStatus.ACTIVE,
            mergedIntoId = null,
            mergedAt = null,
            mergedBy = null,
            createdAt = now,
            updatedAt = now,
            createdBy = aliceId,
            updatedBy = aliceId,
            rowVersion = 0,
        )

        val reloaded = withPhiContext {
            patientRepository.saveAndFlush(entity)
            patientRepository.findById(id).orElseThrow()
        }

        assertThat(reloaded.id).isEqualTo(id)
        assertThat(reloaded.tenantId).isEqualTo(tenantId)
        assertThat(reloaded.mrn).isEqualTo("MAP-0001")
        assertThat(reloaded.mrnSource).isEqualTo(MrnSource.GENERATED)
        assertThat(reloaded.nameGiven).isEqualTo("Ada")
        assertThat(reloaded.nameFamily).isEqualTo("Lovelace")
        assertThat(reloaded.nameMiddle).isEqualTo("Augusta")
        assertThat(reloaded.namePrefix).isEqualTo("Ms.")
        assertThat(reloaded.preferredName).isEqualTo("Ada")
        assertThat(reloaded.birthDate).isEqualTo(LocalDate.of(1815, 12, 10))
        assertThat(reloaded.administrativeSexWire).isEqualTo("female")
        assertThat(reloaded.administrativeSex).isEqualTo(AdministrativeSex.FEMALE)
        assertThat(reloaded.sexAssignedAtBirth).isEqualTo("F")
        assertThat(reloaded.preferredLanguage).isEqualTo("en")
        assertThat(reloaded.status).isEqualTo(PatientStatus.ACTIVE)
        assertThat(reloaded.createdBy).isEqualTo(aliceId)
        assertThat(reloaded.updatedBy).isEqualTo(aliceId)
        assertThat(reloaded.rowVersion).isEqualTo(0L)
    }

    @Test
    fun `saving a patient_identifier roundtrips via repository`() {
        val patientId = UUID.randomUUID()
        val identifierId = UUID.randomUUID()
        val now = Instant.parse("2026-01-15T10:00:00Z")

        val reloaded = withPhiContext {
            patientRepository.saveAndFlush(
                newPatient(patientId, mrn = "MAP-0002", now = now),
            )
            patientIdentifierRepository.saveAndFlush(
                PatientIdentifierEntity(
                    id = identifierId,
                    patientId = patientId,
                    type = PatientIdentifierType.INSURANCE_MEMBER,
                    issuer = "Aetna",
                    value = "M-12345",
                    validFrom = now,
                    validTo = null,
                    createdAt = now,
                    updatedAt = now,
                    rowVersion = 0,
                ),
            )
            patientIdentifierRepository.findById(identifierId).orElseThrow()
        }

        assertThat(reloaded.patientId).isEqualTo(patientId)
        assertThat(reloaded.type).isEqualTo(PatientIdentifierType.INSURANCE_MEMBER)
        assertThat(reloaded.issuer).isEqualTo("Aetna")
        assertThat(reloaded.value).isEqualTo("M-12345")
        assertThat(reloaded.validFrom).isEqualTo(now)
    }

    @Test
    fun `UNIQUE (tenant_id, mrn) rejects duplicates`() {
        val admin = JdbcTemplate(adminDataSource)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        insertRawPatient(admin, a, tenantId, mrn = "DUP-1", administrativeSex = "male", status = "ACTIVE")
        assertThatThrownBy {
            insertRawPatient(admin, b, tenantId, mrn = "DUP-1", administrativeSex = "male", status = "ACTIVE")
        }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `CHECK constraint rejects invalid administrative_sex value`() {
        val admin = JdbcTemplate(adminDataSource)
        assertThatThrownBy {
            insertRawPatient(
                admin, UUID.randomUUID(), tenantId,
                mrn = "CK-SEX", administrativeSex = "MALE", // wrong case — must be lowercase
                status = "ACTIVE",
            )
        }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `CHECK constraint rejects invalid status value`() {
        val admin = JdbcTemplate(adminDataSource)
        assertThatThrownBy {
            insertRawPatient(
                admin, UUID.randomUUID(), tenantId,
                mrn = "CK-STATUS", administrativeSex = "male",
                status = "ARCHIVED", // not in {ACTIVE, MERGED_AWAY, DELETED}
            )
        }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `CHECK ck_clinical_patient_merged_fields_coherent rejects incoherent merge fields`() {
        val admin = JdbcTemplate(adminDataSource)
        // MERGED_AWAY row without merged_into_id + merged_at + merged_by
        // must be refused by the coherence check.
        assertThatThrownBy {
            admin.update(
                """
                INSERT INTO clinical.patient(
                    id, tenant_id, mrn, mrn_source,
                    name_given, name_family, birth_date, administrative_sex,
                    status, created_at, updated_at, created_by, updated_by, row_version
                ) VALUES (
                    ?, ?, 'MRG-BAD', 'GENERATED',
                    'A', 'B', DATE '1990-01-01', 'unknown',
                    'MERGED_AWAY', NOW(), NOW(), ?, ?, 0
                )
                """.trimIndent(),
                UUID.randomUUID(), tenantId, aliceId, aliceId,
            )
        }.isInstanceOf(Exception::class.java)
    }

    // ---- helpers ----

    private fun newPatient(id: UUID, mrn: String, now: Instant) = PatientEntity(
        id = id,
        tenantId = tenantId,
        mrn = mrn,
        mrnSource = MrnSource.GENERATED,
        nameGiven = "First",
        nameFamily = "Last",
        birthDate = LocalDate.of(1980, 1, 1),
        administrativeSexWire = AdministrativeSex.UNKNOWN.wireValue,
        createdAt = now,
        updatedAt = now,
        createdBy = aliceId,
        updatedBy = aliceId,
    )

    private fun insertRawPatient(
        jdbc: JdbcTemplate,
        id: UUID,
        tenant: UUID,
        mrn: String,
        administrativeSex: String,
        status: String,
    ) {
        jdbc.update(
            """
            INSERT INTO clinical.patient(
                id, tenant_id, mrn, mrn_source,
                name_given, name_family, birth_date, administrative_sex,
                status, created_at, updated_at, created_by, updated_by, row_version
            ) VALUES (
                ?, ?, ?, 'GENERATED',
                'A', 'B', DATE '1980-01-01', ?,
                ?, NOW(), NOW(), ?, ?, 0
            )
            """.trimIndent(),
            id, tenant, mrn, administrativeSex, status, aliceId, aliceId,
        )
    }

    /**
     * Runs [block] in a Spring-managed transaction on the primary
     * (medcore_app) datasource with both RLS GUCs set — matches the
     * runtime PHI write pattern (PhiRlsTxHook applies GUCs inside
     * the gate's tx before the handler runs).
     */
    private fun <R> withPhiContext(block: () -> R): R {
        val template = TransactionTemplate(txManager)
        return template.execute {
            tenancySessionContext.apply(userId = aliceId, tenantId = tenantId)
            block()
        }!!
    }
}
