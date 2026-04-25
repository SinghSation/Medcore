package com.medcore.clinical.problem.read

import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.clinical.problem.persistence.ProblemRepository
import com.medcore.clinical.problem.write.ProblemSnapshot
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.stereotype.Component

/**
 * Handler for [ListPatientProblemsCommand] (Phase 4E.2).
 *
 * Runs inside [com.medcore.platform.read.ReadGate]'s
 * transaction with both RLS GUCs set by `PhiRlsTxHook`.
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug.
 * 2. Load patient; verify `patient.tenantId == tenant.id`
 *    (cross-tenant probe → 404 — no existence leak).
 * 3. Fetch all problems for `(tenant_id, patient_id)` via
 *    [ProblemRepository.findByTenantIdAndPatientIdOrdered].
 *    The query runs under V25's SELECT RLS policy — RLS-
 *    invisible rows (cross-tenant, etc.) are filtered at
 *    the DB layer. A zero-row result means "no problems
 *    recorded for this patient" — STILL a disclosure event,
 *    audited once with `count:0`.
 * 4. Map entities to [ProblemSnapshot]s.
 *
 * ### Filtering by status
 *
 * The handler does NOT filter by status — it returns all
 * rows. The audit count then matches what RLS allowed the
 * caller to see (compliance accuracy). The frontend chart-
 * context surface decides whether to show ENTERED_IN_ERROR
 * (typically hidden behind a toggle in the management modal).
 * Mirrors `ListPatientAllergiesHandler` discipline.
 */
@Component
class ListPatientProblemsHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
    private val problemRepository: ProblemRepository,
) {

    fun handle(
        command: ListPatientProblemsCommand,
        @Suppress("UNUSED_PARAMETER") context: WriteContext,
    ): ListPatientProblemsResult {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val patient = patientRepository.findById(command.patientId).orElse(null)
            ?: throw EntityNotFoundException("patient not found: ${command.patientId}")
        if (patient.tenantId != tenant.id) {
            throw EntityNotFoundException("patient not found: ${command.patientId}")
        }

        val problems = problemRepository.findByTenantIdAndPatientIdOrdered(
            tenantId = tenant.id,
            patientId = patient.id,
        )
        return ListPatientProblemsResult(
            items = problems.map { ProblemSnapshot.from(it) },
        )
    }
}
