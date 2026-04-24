package com.medcore.clinical.encounter.read

import java.util.UUID

/**
 * Command for
 * `GET /api/v1/tenants/{slug}/patients/{patientId}/encounters`
 * (Phase 4C.3).
 *
 * Lists all encounters for a patient in the tenant, newest-first.
 * No pagination in 4C.3 — bounded in practice (a patient's
 * encounter history is naturally limited; cursor-based pagination
 * is a later slice if tenant scale warrants).
 *
 * URL shape mirrors the POST surface from 4C.1
 * (`POST /patients/{id}/encounters`) so reads + writes share the
 * path, which matches how FHIR itself models encounter-by-subject.
 */
data class ListPatientEncountersCommand(
    val slug: String,
    val patientId: UUID,
)
