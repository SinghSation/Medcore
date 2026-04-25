package com.medcore.clinical.allergy.read

import com.medcore.clinical.allergy.persistence.AllergyRepository
import com.medcore.clinical.allergy.write.AllergySnapshot
import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.stereotype.Component

/**
 * Handler for [ListPatientAllergiesCommand] (Phase 4E.1).
 *
 * Runs inside [com.medcore.platform.read.ReadGate]'s
 * transaction with both RLS GUCs set by `PhiRlsTxHook`.
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug.
 * 2. Load patient; verify `patient.tenantId == tenant.id`
 *    (cross-tenant probe → 404 — no existence leak; mirrors
 *    `ListPatientEncountersHandler`).
 * 3. Fetch all allergies for `(tenant_id, patient_id)` via
 *    [AllergyRepository.findByTenantIdAndPatientIdOrdered].
 *    The query runs under V24's SELECT RLS policy — RLS-
 *    invisible rows (cross-tenant, etc.) are filtered at the
 *    DB layer. A zero-row result means "no allergies recorded
 *    for this patient" — STILL a disclosure event, audited
 *    once with `count:0`.
 * 4. Map entities to [AllergySnapshot]s.
 *
 * ### Banner vs management filtering
 *
 * The handler does NOT filter by status. Returning all rows
 * lets the audit count match what RLS allowed the caller to
 * see (compliance accuracy), and the frontend filters to
 * ACTIVE for the banner / shows all for the management view.
 */
@Component
class ListPatientAllergiesHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
    private val allergyRepository: AllergyRepository,
) {

    fun handle(
        command: ListPatientAllergiesCommand,
        @Suppress("UNUSED_PARAMETER") context: WriteContext,
    ): ListPatientAllergiesResult {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val patient = patientRepository.findById(command.patientId).orElse(null)
            ?: throw EntityNotFoundException("patient not found: ${command.patientId}")
        if (patient.tenantId != tenant.id) {
            throw EntityNotFoundException("patient not found: ${command.patientId}")
        }

        val allergies = allergyRepository.findByTenantIdAndPatientIdOrdered(
            tenantId = tenant.id,
            patientId = patient.id,
        )
        return ListPatientAllergiesResult(
            items = allergies.map { AllergySnapshot.from(it) },
        )
    }
}
