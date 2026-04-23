package com.medcore.clinical.patient.read

/**
 * Command for `GET /api/v1/tenants/{slug}/patients` (Phase 4B.1,
 * first list-shaped read in Medcore).
 *
 * Follows `clinical-write-pattern.md` §12 "Read path" — same
 * shape discipline as [GetPatientCommand] with two additional
 * pagination parameters.
 *
 * ### PHI boundary
 *
 * The command carries no PHI. `slug` is the tenant identifier
 * (caller-facing), `limit` and `offset` are pagination knobs.
 * The handler's result (`ListPatientsResult`) carries PHI in
 * the `items` list; the auditor never echoes PHI into the
 * audit reason slug.
 *
 * ### Bounds
 *
 * Controller parses `limit` / `offset` from query string with
 * default `limit = 20` and `offset = 0`. Out-of-range values
 * throw `WriteValidationException` → 422 at the controller
 * layer. Handler assumes already-bounded values.
 */
data class ListPatientsCommand(
    val slug: String,
    val limit: Int,
    val offset: Int,
)
