package com.medcore.clinical.encounter.write

import com.medcore.clinical.encounter.model.EncounterClass
import java.util.UUID

/**
 * Command for
 * `POST /api/v1/tenants/{slug}/patients/{patientId}/encounters`
 * (Phase 4C.1, VS1 Chunk D).
 *
 * Starts a new encounter in status `IN_PROGRESS`. No `PLANNED`
 * path in 4C.1 — scheduling lands in Phase 4B.
 *
 * Immutable data class carrying only the minimum: tenant slug,
 * patient UUID, and encounter class. The handler resolves the
 * tenant, validates the patient exists and belongs to the
 * tenant (RLS already filters cross-tenant), mints the UUID,
 * and INSERTs the row with `status = IN_PROGRESS` and
 * `started_at = now()`.
 */
data class StartEncounterCommand(
    val slug: String,
    val patientId: UUID,
    val encounterClass: EncounterClass,
)
