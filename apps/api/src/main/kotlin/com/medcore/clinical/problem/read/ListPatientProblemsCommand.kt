package com.medcore.clinical.problem.read

import com.medcore.platform.read.pagination.PageRequest
import java.util.UUID

/**
 * Command for
 * `GET /api/v1/tenants/{slug}/patients/{patientId}/problems`
 * (Phase 4E.2, paginated as of platform-pagination chunk E,
 * ADR-009).
 *
 * Lists problems with status priority: ACTIVE first, then
 * INACTIVE, then RESOLVED, then ENTERED_IN_ERROR. Within each
 * bucket, newest-first by `createdAt DESC, id DESC`.
 *
 * **RESOLVED ≠ INACTIVE** — load-bearing 4E.2 invariant
 * preserved by the cursor encoding `(status_priority,
 * createdAt, id)` per ADR-009 §2.5.
 *
 * Cursor implementation:
 * [com.medcore.platform.read.pagination.BucketedCursor] with
 * `k = "clinical.problem.v1"`, `bucket` = priority (0/1/2/3),
 * `ts` = createdAt, `id` = entity id.
 *
 * Wire shape: `?pageSize=50&cursor=<opaque>`.
 */
data class ListPatientProblemsCommand(
    val slug: String,
    val patientId: UUID,
    val pageRequest: PageRequest,
)
