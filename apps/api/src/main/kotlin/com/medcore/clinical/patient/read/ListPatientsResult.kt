package com.medcore.clinical.patient.read

import com.medcore.clinical.patient.write.PatientSnapshot

/**
 * Handler result for [ListPatientsCommand] (Phase 4B.1).
 *
 * Carries the paginated page of patient snapshots plus the
 * total visible count under the caller's RLS envelope.
 *
 * ### `totalCount` semantics
 *
 * The count reflects rows visible to the caller under V14 RLS,
 * NOT the raw tenant row count. A caller who (hypothetically)
 * holds tenant membership but is RLS-filtered from subsets of
 * the patient table will see a count consistent with their
 * visibility. For 4B.1 the V14 `p_patient_select` policy hides
 * only `status = 'DELETED'` and cross-tenant rows, so for any
 * ACTIVE member of a single tenant the count matches the
 * tenant's active patient population.
 *
 * ### Cost
 *
 * A second `COUNT(*)` query runs alongside the page query.
 * Acceptable at this scale; a future slice may replace with a
 * cursor-based pagination model if latency or lock contention
 * becomes material. Tracked as forward-looking note, not a
 * 4B.1 scope item.
 */
data class ListPatientsResult(
    val items: List<PatientSnapshot>,
    val totalCount: Long,
    val limit: Int,
    val offset: Int,
) {
    val hasMore: Boolean
        get() = offset + items.size < totalCount
}
