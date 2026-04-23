package com.medcore.clinical.patient.fhir

import com.medcore.clinical.patient.model.AdministrativeSex
import com.medcore.clinical.patient.model.MrnSource
import com.medcore.clinical.patient.model.PatientStatus
import com.medcore.clinical.patient.write.PatientSnapshot
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [PatientFhirMapper] (Phase 4A.5).
 *
 * No Spring context needed — `PatientFhirMapper` is a pure
 * snapshot-to-DTO mapper. Verifies every field projection
 * listed in the mapper's KDoc:
 *
 *   - Scalars (id, active, gender, birthDate, meta)
 *   - Identifier with tenant-scoped URN + MRN type coding
 *   - Official name (family + given[] with middle when set,
 *     prefix + suffix when set)
 *   - Usual name (preferredName → `name[1]` with `use=usual`)
 *   - US Core birth-sex extension (present only when source set)
 *   - US Core gender-identity extension (present only when set)
 *   - Communication entry (present only when preferredLanguage set)
 *   - DELETED patient → `active = false`
 *
 * Also proves the "omit absent fields" discipline — nullable
 * source columns produce NO corresponding FHIR structure
 * (FHIR consumers expect absent fields, not empty arrays or
 * `null` values).
 */
class PatientFhirMapperTest {

    private val mapper = PatientFhirMapper()

    private val baseSnapshot = PatientSnapshot(
        id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        tenantId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        mrn = "000042",
        mrnSource = MrnSource.GENERATED,
        nameGiven = "Ada",
        nameFamily = "Lovelace",
        nameMiddle = null,
        nameSuffix = null,
        namePrefix = null,
        preferredName = null,
        birthDate = LocalDate.of(1960, 5, 15),
        administrativeSex = AdministrativeSex.FEMALE,
        sexAssignedAtBirth = null,
        genderIdentityCode = null,
        preferredLanguage = null,
        status = PatientStatus.ACTIVE,
        createdAt = Instant.parse("2026-04-01T12:00:00Z"),
        updatedAt = Instant.parse("2026-04-02T12:00:00Z"),
        createdBy = UUID.randomUUID(),
        updatedBy = UUID.randomUUID(),
        rowVersion = 3L,
    )

    @Test
    fun `minimal snapshot maps scalars and meta correctly`() {
        val fhir = mapper.toFhir(baseSnapshot)

        assertThat(fhir.resourceType).isEqualTo("Patient")
        assertThat(fhir.id).isEqualTo("11111111-1111-1111-1111-111111111111")
        assertThat(fhir.active).isTrue()
        assertThat(fhir.gender).isEqualTo("female")
        assertThat(fhir.birthDate).isEqualTo(LocalDate.of(1960, 5, 15))
        assertThat(fhir.meta.versionId).isEqualTo("3")
        assertThat(fhir.meta.lastUpdated)
            .isEqualTo(Instant.parse("2026-04-02T12:00:00Z"))
    }

    @Test
    fun `MRN emits as tenant-scoped urn identifier with MR type coding`() {
        val fhir = mapper.toFhir(baseSnapshot)

        assertThat(fhir.identifier).hasSize(1)
        val identifier = fhir.identifier.single()
        assertThat(identifier.use).isEqualTo("usual")
        assertThat(identifier.value).isEqualTo("000042")
        assertThat(identifier.system)
            .isEqualTo("urn:medcore:tenant:22222222-2222-2222-2222-222222222222:mrn")
        val coding = identifier.type!!.coding.single()
        assertThat(coding.system)
            .isEqualTo("http://terminology.hl7.org/CodeSystem/v2-0203")
        assertThat(coding.code).isEqualTo("MR")
        assertThat(coding.display).isEqualTo("Medical record number")
    }

    @Test
    fun `official name contains family and single given when middle absent`() {
        val fhir = mapper.toFhir(baseSnapshot)

        assertThat(fhir.name).hasSize(1) // only official — no preferred name
        val official = fhir.name.single()
        assertThat(official.use).isEqualTo("official")
        assertThat(official.family).isEqualTo("Lovelace")
        assertThat(official.given).containsExactly("Ada")
        assertThat(official.prefix).isEmpty()
        assertThat(official.suffix).isEmpty()
    }

    @Test
    fun `official name includes middle name in given array when set`() {
        val fhir = mapper.toFhir(baseSnapshot.copy(nameMiddle = "Byron"))
        val official = fhir.name.single()
        assertThat(official.given).containsExactly("Ada", "Byron")
    }

    @Test
    fun `prefix and suffix carry through when set`() {
        val fhir = mapper.toFhir(baseSnapshot.copy(namePrefix = "Ms.", nameSuffix = "PhD"))
        val official = fhir.name.single()
        assertThat(official.prefix).containsExactly("Ms.")
        assertThat(official.suffix).containsExactly("PhD")
    }

    @Test
    fun `preferredName emits a second name entry with use=usual`() {
        val fhir = mapper.toFhir(baseSnapshot.copy(preferredName = "Ada"))
        assertThat(fhir.name).hasSize(2)
        val usual = fhir.name[1]
        assertThat(usual.use).isEqualTo("usual")
        assertThat(usual.text).isEqualTo("Ada")
    }

    @Test
    fun `us-core birthsex extension emitted only when sex_assigned_at_birth is set`() {
        val withSab = mapper.toFhir(baseSnapshot.copy(sexAssignedAtBirth = "F"))
        assertThat(withSab.extension).hasSize(1)
        val ext = withSab.extension.single()
        assertThat(ext.url)
            .isEqualTo("http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex")
        assertThat(ext.valueCode).isEqualTo("F")

        val without = mapper.toFhir(baseSnapshot) // sexAssignedAtBirth = null
        assertThat(without.extension).isEmpty()
    }

    @Test
    fun `us-core gender-identity extension emitted only when gender_identity_code is set`() {
        val withCode = mapper.toFhir(baseSnapshot.copy(genderIdentityCode = "gender-fluid"))
        assertThat(withCode.extension).hasSize(1)
        val ext = withCode.extension.single()
        assertThat(ext.url)
            .isEqualTo("http://hl7.org/fhir/us/core/StructureDefinition/us-core-genderIdentity")
        assertThat(ext.valueString).isEqualTo("gender-fluid")
        assertThat(ext.valueCode).isNull() // NOT valueCode for gender-identity
    }

    @Test
    fun `both extensions emitted when both source columns set`() {
        val fhir = mapper.toFhir(
            baseSnapshot.copy(
                sexAssignedAtBirth = "F",
                genderIdentityCode = "gender-fluid",
            ),
        )
        assertThat(fhir.extension).hasSize(2)
        val urls = fhir.extension.map { it.url }
        assertThat(urls).containsExactlyInAnyOrder(
            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex",
            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-genderIdentity",
        )
    }

    @Test
    fun `communication entry emitted only when preferredLanguage set`() {
        val with = mapper.toFhir(baseSnapshot.copy(preferredLanguage = "en-US"))
        assertThat(with.communication).hasSize(1)
        val comm = with.communication.single()
        assertThat(comm.preferred).isTrue()
        val coding = comm.language.coding.single()
        assertThat(coding.system).isEqualTo("urn:ietf:bcp:47")
        assertThat(coding.code).isEqualTo("en-US")

        val without = mapper.toFhir(baseSnapshot)
        assertThat(without.communication).isEmpty()
    }

    @Test
    fun `DELETED status maps active=false`() {
        val fhir = mapper.toFhir(baseSnapshot.copy(status = PatientStatus.DELETED))
        assertThat(fhir.active).isFalse()
    }

    @Test
    fun `MERGED_AWAY status maps active=false`() {
        val fhir = mapper.toFhir(baseSnapshot.copy(status = PatientStatus.MERGED_AWAY))
        assertThat(fhir.active)
            .describedAs("MERGED_AWAY patients are not the canonical active record")
            .isFalse()
    }

    @Test
    fun `all nullable fields absent — no FHIR structure emitted for them`() {
        val fhir = mapper.toFhir(baseSnapshot)

        // Name has only official (no usual).
        assertThat(fhir.name.map { it.use }).containsExactly("official")

        // Zero extensions.
        assertThat(fhir.extension).isEmpty()

        // Zero communication entries.
        assertThat(fhir.communication).isEmpty()

        // Identifier still present (MRN is mandatory).
        assertThat(fhir.identifier).hasSize(1)
    }
}
