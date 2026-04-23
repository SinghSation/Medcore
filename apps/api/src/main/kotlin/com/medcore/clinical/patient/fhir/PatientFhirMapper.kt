package com.medcore.clinical.patient.fhir

import com.medcore.clinical.patient.model.PatientStatus
import com.medcore.clinical.patient.write.PatientSnapshot
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * Converts [PatientSnapshot] (Medcore's internal read shape)
 * into a minimal FHIR R4 [FhirPatient] (Phase 4A.5).
 *
 * ### Scope — explicit
 *
 * Minimal FHIR R4 Patient mapping influenced by US Core
 * Patient. **Not** a US Core profile-conformant implementation.
 * Maps only the fields Medcore currently stores on
 * `clinical.patient` (4A.1 / 4A.2). Satellite identifiers
 * (4A.3) + address + telecom + race/ethnicity are NOT mapped
 * here; adding them is a future additive slice.
 *
 * ### Identifier system URI
 *
 * The Medcore MRN emits as a FHIR Identifier with:
 *   - `use = "usual"` (the day-to-day identifier)
 *   - `system = "urn:medcore:tenant:{tenant-uuid}:mrn"`
 *
 * The URN form is an **interim** choice (4A.5 carry-forward).
 * A canonical DNS-based or OID-based system URI is a future
 * concern when Medcore commits to a public identity-system
 * namespace.
 *
 * ### US Core extensions mapped when source columns populated
 *
 * - `sex_assigned_at_birth` → `us-core-birthsex` extension
 *   with `valueCode` ∈ {M, F, UNK}
 * - `gender_identity_code` → `us-core-genderIdentity`
 *   extension with `valueString` (4A.5 emits free-string
 *   since the source column is a free-string input today;
 *   future slice may upgrade to `valueCoding` when SNOMED-
 *   coded input lands).
 *
 * ### Why hand-rolled and not HAPI FHIR
 *
 * HAPI FHIR is a large dependency designed for full-server
 * implementations. 4A.5 maps ONE resource with ~15 field
 * projections. Hand-rolled typed DTOs + Jackson keeps the
 * dependency graph lean and the mapping logic completely
 * visible. When Medcore expands to multiple FHIR resources
 * + search + bundles, the HAPI-vs-hand-rolled question
 * revisits as an ADR.
 */
@Component
class PatientFhirMapper {

    /**
     * Produces the FHIR Patient resource for [snapshot]. The
     * tenant UUID is read from the snapshot and emitted as
     * part of the identifier `system` URI.
     */
    fun toFhir(snapshot: PatientSnapshot): FhirPatient = FhirPatient(
        resourceType = "Patient",
        id = snapshot.id.toString(),
        meta = FhirMeta(
            versionId = snapshot.rowVersion.toString(),
            lastUpdated = snapshot.updatedAt,
        ),
        identifier = listOf(mrnIdentifier(snapshot.tenantId, snapshot.mrn)),
        active = snapshot.status == PatientStatus.ACTIVE,
        name = buildNames(snapshot),
        gender = snapshot.administrativeSex.wireValue,
        birthDate = snapshot.birthDate,
        extension = buildExtensions(snapshot),
        communication = buildCommunication(snapshot),
    )

    private fun mrnIdentifier(tenantId: UUID, mrn: String): FhirIdentifier =
        FhirIdentifier(
            use = "usual",
            system = "urn:medcore:tenant:$tenantId:mrn",
            value = mrn,
            type = FhirCodeableConcept(
                coding = listOf(
                    FhirCoding(
                        system = "http://terminology.hl7.org/CodeSystem/v2-0203",
                        code = "MR",
                        display = "Medical record number",
                    ),
                ),
            ),
        )

    private fun buildNames(snapshot: PatientSnapshot): List<FhirHumanName> {
        val names = mutableListOf<FhirHumanName>()
        // Official name — always present for 4A.5 (nameGiven /
        // nameFamily are NOT NULL in V14).
        names += FhirHumanName(
            use = "official",
            family = snapshot.nameFamily,
            given = listOfNotNull(
                snapshot.nameGiven,
                snapshot.nameMiddle,
            ),
            prefix = listOfNotNull(snapshot.namePrefix),
            suffix = listOfNotNull(snapshot.nameSuffix),
        )
        // Usual (preferred) name — only when the column is set.
        snapshot.preferredName?.let { preferred ->
            names += FhirHumanName(
                use = "usual",
                text = preferred,
            )
        }
        return names
    }

    private fun buildExtensions(snapshot: PatientSnapshot): List<FhirExtension> {
        val extensions = mutableListOf<FhirExtension>()
        snapshot.sexAssignedAtBirth?.let { sab ->
            extensions += FhirExtension(
                url = US_CORE_BIRTHSEX_EXTENSION,
                valueCode = sab,
            )
        }
        snapshot.genderIdentityCode?.let { code ->
            extensions += FhirExtension(
                url = US_CORE_GENDER_IDENTITY_EXTENSION,
                valueString = code,
            )
        }
        return extensions
    }

    private fun buildCommunication(snapshot: PatientSnapshot): List<FhirCommunication> =
        snapshot.preferredLanguage?.let { lang ->
            listOf(
                FhirCommunication(
                    language = FhirCodeableConcept(
                        coding = listOf(
                            FhirCoding(
                                system = BCP_47_SYSTEM,
                                code = lang,
                            ),
                        ),
                    ),
                    preferred = true,
                ),
            )
        } ?: emptyList()

    private companion object {
        const val US_CORE_BIRTHSEX_EXTENSION: String =
            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex"
        const val US_CORE_GENDER_IDENTITY_EXTENSION: String =
            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-genderIdentity"
        const val BCP_47_SYSTEM: String = "urn:ietf:bcp:47"
    }
}
