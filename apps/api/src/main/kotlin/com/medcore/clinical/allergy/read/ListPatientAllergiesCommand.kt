package com.medcore.clinical.allergy.read

import java.util.UUID

/**
 * Command for
 * `GET /api/v1/tenants/{slug}/patients/{patientId}/allergies`
 * (Phase 4E.1).
 *
 * Lists every allergy on a patient — ACTIVE first, then
 * INACTIVE, then ENTERED_IN_ERROR (per
 * [com.medcore.clinical.allergy.persistence.AllergyRepository.findByTenantIdAndPatientIdOrdered]).
 * The frontend banner filters to ACTIVE; the management view
 * shows all.
 *
 * Un-paginated in 4E.1 — bounded in practice. A single
 * patient's allergy slate stays small (typical is < 10).
 * Cursor-based pagination is a later slice if a research /
 * import workflow accumulates hundreds.
 */
data class ListPatientAllergiesCommand(
    val slug: String,
    val patientId: UUID,
)
