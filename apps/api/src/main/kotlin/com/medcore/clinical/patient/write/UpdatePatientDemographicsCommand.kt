package com.medcore.clinical.patient.write

import com.medcore.clinical.patient.model.AdministrativeSex
import java.time.LocalDate
import java.util.UUID

/**
 * Command for
 * `PATCH /api/v1/tenants/{slug}/patients/{patientId}` — partial
 * update of a patient's demographics (Phase 4A.2).
 *
 * ### Partial-update semantics
 *
 * Every patchable field is wrapped in [Patchable], which carries
 * three mutually-exclusive states — [Patchable.Absent],
 * [Patchable.Clear], [Patchable.Set]. See the [Patchable] KDoc
 * for the RFC 7396 JSON Merge Patch contract.
 *
 * ### Optimistic concurrency
 *
 * [expectedRowVersion] is parsed from the `If-Match` request
 * header (REQUIRED, 428 if absent) and compared to the loaded
 * patient's current `row_version`. Mismatch → 409
 * `resource.conflict` with `details.reason = stale_row`.
 *
 * ### Fields NOT patchable
 *
 *   - `id`, `tenant_id` — immutable identity.
 *   - `mrn`, `mrn_source` — once minted, the MRN is the durable
 *     identifier. Changes require a separate (future) re-issue
 *     slice with its own compliance review.
 *   - `status`, `merged_into_id`, `merged_at`, `merged_by` — the
 *     merge workflow is a dedicated slice (4A.N).
 *   - `created_*`, `row_version` — audit/concurrency state.
 *
 * ### PHI discipline
 *
 * Every wrapped value is PHI. The command MUST NOT appear in log
 * lines, MDC keys, or tracing attributes. `PatientLogPhiLeakageTest`
 * enforces.
 */
data class UpdatePatientDemographicsCommand(
    val slug: String,
    val patientId: UUID,
    val expectedRowVersion: Long,

    val nameGiven: Patchable<String> = Patchable.Absent,
    val nameFamily: Patchable<String> = Patchable.Absent,
    val nameMiddle: Patchable<String> = Patchable.Absent,
    val nameSuffix: Patchable<String> = Patchable.Absent,
    val namePrefix: Patchable<String> = Patchable.Absent,
    val preferredName: Patchable<String> = Patchable.Absent,
    val birthDate: Patchable<LocalDate> = Patchable.Absent,
    val administrativeSex: Patchable<AdministrativeSex> = Patchable.Absent,
    val sexAssignedAtBirth: Patchable<String> = Patchable.Absent,
    val genderIdentityCode: Patchable<String> = Patchable.Absent,
    val preferredLanguage: Patchable<String> = Patchable.Absent,
) {
    /**
     * Enumerates the names of fields that carry a concrete change
     * request ([Patchable.Set] or [Patchable.Clear]). Used by the
     * auditor's `reason` slug (`intent:...|fields:<csv>`) and by
     * the handler's no-op detector.
     */
    fun changingFieldNames(): Set<String> {
        val names = LinkedHashSet<String>()
        fun add(field: String, patchable: Patchable<*>) {
            if (patchable !is Patchable.Absent) names += field
        }
        add("nameGiven", nameGiven)
        add("nameFamily", nameFamily)
        add("nameMiddle", nameMiddle)
        add("nameSuffix", nameSuffix)
        add("namePrefix", namePrefix)
        add("preferredName", preferredName)
        add("birthDate", birthDate)
        add("administrativeSex", administrativeSex)
        add("sexAssignedAtBirth", sexAssignedAtBirth)
        add("genderIdentityCode", genderIdentityCode)
        add("preferredLanguage", preferredLanguage)
        return names
    }
}
