package com.medcore.clinical.allergy.write

import com.medcore.clinical.allergy.model.AllergySeverity
import java.time.LocalDate
import java.util.UUID

/**
 * Command for
 * `POST /api/v1/tenants/{slug}/patients/{patientId}/allergies`
 * (Phase 4E.1).
 *
 * Records a new allergy on a patient. Status is always ACTIVE
 * on insert — the lifecycle (INACTIVE / ENTERED_IN_ERROR) is
 * exposed through the PATCH surface, not on initial creation.
 *
 * `recordedInEncounterId` is optional: clinically meaningful
 * provenance for "this allergy was first recorded during
 * encounter X." Allergies recorded outside an encounter
 * context (future intake-form path) leave it null.
 *
 * `substanceText` is PHI-bordering — substance names like
 * "Penicillin" are not personally identifiable in isolation
 * but combined with patient identity they form PHI. Audit
 * row never carries the substance text — only the closed-enum
 * severity token.
 */
data class AddAllergyCommand(
    val slug: String,
    val patientId: UUID,
    val substanceText: String,
    val severity: AllergySeverity,
    val reactionText: String? = null,
    val onsetDate: LocalDate? = null,
    val recordedInEncounterId: UUID? = null,
)
