package com.medcore.clinical.allergy.read

import com.medcore.platform.read.pagination.PageRequest
import java.util.UUID

/**
 * Command for
 * `GET /api/v1/tenants/{slug}/patients/{patientId}/allergies`
 * (Phase 4E.1, paginated as of platform-pagination chunk D,
 * ADR-009).
 *
 * Lists allergies for a patient with banker-style status
 * priority: ACTIVE first, then INACTIVE, then ENTERED_IN_ERROR.
 * Within each bucket, newest-first by `createdAt DESC, id DESC`.
 *
 * Cursor-based pagination — the cursor encodes the FULL sort
 * tuple `(status_priority, createdAt, id)` per ADR-009 §2.2,
 * so cross-bucket walks are correct (the cursor knows which
 * bucket the previous page ended in).
 *
 * Wire shape: `?pageSize=50&cursor=<opaque>`.
 *
 * Cursor implementation:
 * [com.medcore.platform.read.pagination.BucketedCursor] with
 * `k = "clinical.allergy.v1"`, `bucket` = priority (0/1/3),
 * `ts` = createdAt, `id` = entity id.
 */
data class ListPatientAllergiesCommand(
    val slug: String,
    val patientId: UUID,
    val pageRequest: PageRequest,
)
