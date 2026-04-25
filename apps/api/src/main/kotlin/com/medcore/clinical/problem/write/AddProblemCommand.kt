package com.medcore.clinical.problem.write

import com.medcore.clinical.problem.model.ProblemSeverity
import java.time.LocalDate
import java.util.UUID

/**
 * Command for
 * `POST /api/v1/tenants/{slug}/patients/{patientId}/problems`
 * (Phase 4E.2).
 *
 * Records a new problem on a patient. Status is always ACTIVE
 * on insert — the lifecycle (INACTIVE / RESOLVED /
 * ENTERED_IN_ERROR) is exposed through the PATCH surface, not
 * on initial creation.
 *
 * `severity` is **nullable** per locked Q3 of the 4E.2 plan
 * (FHIR-aligned: many problems have no clinically meaningful
 * severity — "history of appendectomy", "smoking cessation
 * goal"). Distinct from [com.medcore.clinical.allergy.write.AddAllergyCommand]
 * where severity is required.
 *
 * `recordedInEncounterId` is optional: clinically meaningful
 * provenance for "this problem was first documented during
 * encounter X." Problems entered outside an encounter context
 * (intake form, retrospective entry) leave it null.
 *
 * `conditionText` is PHI-bordering — condition descriptions
 * are not personally identifiable in isolation but combined
 * with patient identity they form PHI. Audit row never carries
 * the condition text — see [AddProblemAuditor] reason slug
 * discipline.
 *
 * `abatementDate` is optional and may be set on insert when
 * recording a historical problem (e.g., "appendectomy in
 * 2010, recovered fully") — the V25 CHECK constraint
 * `ck_clinical_problem_abatement_after_onset` enforces
 * `abatement >= onset` when both are present; the validator
 * mirrors this for clean 422.
 */
data class AddProblemCommand(
    val slug: String,
    val patientId: UUID,
    val conditionText: String,
    val severity: ProblemSeverity? = null,
    val onsetDate: LocalDate? = null,
    val abatementDate: LocalDate? = null,
    val recordedInEncounterId: UUID? = null,
)
