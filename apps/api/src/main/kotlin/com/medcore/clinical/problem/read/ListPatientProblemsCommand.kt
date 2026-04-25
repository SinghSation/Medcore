package com.medcore.clinical.problem.read

import java.util.UUID

/**
 * Command for
 * `GET /api/v1/tenants/{slug}/patients/{patientId}/problems`
 * (Phase 4E.2).
 *
 * Lists every problem on a patient — ACTIVE first, then
 * INACTIVE, then RESOLVED, then ENTERED_IN_ERROR (per
 * [com.medcore.clinical.problem.persistence.ProblemRepository.findByTenantIdAndPatientIdOrdered]).
 *
 * The frontend "Problems" card filters or sorts as the UX
 * dictates; the management surface inside the modal can show
 * everything including ENTERED_IN_ERROR for data-audit
 * purposes. The handler returns the full set so the audit
 * row's `count` matches what RLS allowed the caller to see.
 *
 * Un-paginated in 4E.2 — bounded in practice. A single
 * patient's problem list stays small (typical 5–20). Cursor-
 * based pagination is a later slice if a research / import
 * workflow accumulates hundreds.
 */
data class ListPatientProblemsCommand(
    val slug: String,
    val patientId: UUID,
)
