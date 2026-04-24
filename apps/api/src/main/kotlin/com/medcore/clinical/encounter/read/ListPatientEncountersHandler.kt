package com.medcore.clinical.encounter.read

import com.medcore.clinical.encounter.persistence.EncounterEntity
import com.medcore.clinical.encounter.persistence.EncounterRepository
import com.medcore.clinical.encounter.write.EncounterSnapshot
import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.stereotype.Component

/**
 * Handler for [ListPatientEncountersCommand] (Phase 4C.3).
 *
 * Runs inside [com.medcore.platform.read.ReadGate]'s transaction
 * with both RLS GUCs set by `PhiRlsTxHook`.
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug.
 * 2. Load the patient; verify `patient.tenantId == tenant.id`.
 *    RLS hides cross-tenant + soft-deleted patients at the DB
 *    layer already; this check converts an RLS-invisible patient
 *    into a clean 404 with no existence leak. Mirrors the
 *    `StartEncounterHandler` belt-and-braces discipline.
 * 3. Fetch encounters via
 *    [EncounterRepository.findByTenantIdAndPatientIdOrdered]. The
 *    query runs under V18's SELECT RLS policy — rows the caller
 *    should not see are filtered at the DB layer; a zero-row
 *    result means "no encounters yet for this patient" (a
 *    legitimate disclosure event still audited once via the list
 *    auditor).
 * 4. Map entities to [EncounterSnapshot]s.
 */
@Component
class ListPatientEncountersHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
) {

    fun handle(
        command: ListPatientEncountersCommand,
        @Suppress("UNUSED_PARAMETER") context: WriteContext,
    ): ListPatientEncountersResult {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val patient = patientRepository.findById(command.patientId).orElse(null)
            ?: throw EntityNotFoundException("patient not found: ${command.patientId}")
        if (patient.tenantId != tenant.id) {
            // Cross-tenant probe: identical 404 — no existence leak.
            throw EntityNotFoundException("patient not found: ${command.patientId}")
        }

        val encounters = encounterRepository.findByTenantIdAndPatientIdOrdered(
            tenantId = tenant.id,
            patientId = patient.id,
        )
        return ListPatientEncountersResult(items = encounters.map { toSnapshot(it) })
    }

    private fun toSnapshot(entity: EncounterEntity): EncounterSnapshot =
        EncounterSnapshot(
            id = entity.id,
            tenantId = entity.tenantId,
            patientId = entity.patientId,
            status = entity.status,
            encounterClass = entity.encounterClass,
            startedAt = entity.startedAt,
            finishedAt = entity.finishedAt,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            createdBy = entity.createdBy,
            updatedBy = entity.updatedBy,
            rowVersion = entity.rowVersion,
        )
}
