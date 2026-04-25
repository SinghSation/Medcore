package com.medcore.clinical.encounter.read

import com.medcore.platform.read.pagination.PageRequest
import java.util.UUID

/**
 * Command for
 * `GET /api/v1/tenants/{slug}/patients/{patientId}/encounters`
 * (Phase 4C.3 — paginated as of platform-pagination chunk C,
 * ADR-009).
 *
 * Lists encounters for a patient newest-first by `startedAt`.
 * Cursor-based pagination — see ADR-009. Wire shape:
 *
 *   `?pageSize=50&cursor=<opaque>`
 *
 * Sort axis: `(startedAt DESC, id DESC)`.
 *   - Cursor encodes the LAST row's `(startedAt, id)`.
 *   - Implementation: [com.medcore.platform.read.pagination.TimeCursor]
 *     with `ascending = false` and `k = "clinical.encounter.v1"`.
 *
 * URL shape mirrors the POST surface from 4C.1 — reads + writes
 * share the path; matches FHIR's encounter-by-subject conv.
 */
data class ListPatientEncountersCommand(
    val slug: String,
    val patientId: UUID,
    val pageRequest: PageRequest,
)
