package com.medcore.clinical.encounter.read

import com.medcore.clinical.encounter.persistence.EncounterEntity
import com.medcore.clinical.encounter.persistence.EncounterRepository
import com.medcore.clinical.encounter.write.EncounterSnapshot
import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.platform.read.pagination.CursorCodec
import com.medcore.platform.read.pagination.PageResponse
import com.medcore.platform.read.pagination.TimeCursor
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Component

/**
 * Handler for [ListPatientEncountersCommand] (Phase 4C.3,
 * paginated as of platform-pagination chunk C, ADR-009).
 *
 * Runs inside [com.medcore.platform.read.ReadGate]'s transaction
 * with both RLS GUCs set by `PhiRlsTxHook`.
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug.
 * 2. Load patient; verify `patient.tenantId == tenant.id`.
 *    Cross-tenant probe → 404 (no existence leak).
 * 3. Decode optional cursor (`null` → first page; non-null →
 *    next page after the encoded `(startedAt, id)` tuple).
 *    Malformed → [com.medcore.platform.read.pagination.CursorMalformedException]
 *    → 422 `cursor|malformed`.
 * 4. Fetch `pageSize + 1` rows.
 * 5. Hand to [PageResponse.fromFetchedPlusOne] — trims, computes
 *    `hasNextPage`, builds `nextCursor` from the LAST trimmed row.
 *
 * ### Cursor shape
 *
 * `TimeCursor(k = "clinical.encounter.v1", ts = createdAt, id, ascending = false)`.
 * The `ts` field carries `createdAt` (always non-null, vs
 * `startedAt` which is nullable in V18). For the current
 * IN_PROGRESS-on-create flow, `createdAt == startedAt`, so
 * the user-visible ordering is unchanged from the pre-pagination
 * 4C.3 query.
 */
@Component
class ListPatientEncountersHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
) {

    fun handle(
        command: ListPatientEncountersCommand,
        @Suppress("UNUSED_PARAMETER") context: WriteContext,
    ): ListPatientEncountersResult {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val patient = patientRepository.findById(command.patientId).orElse(null)
            ?: throw EntityNotFoundException("patient not found: ${command.patientId}")
        if (patient.tenantId != tenant.id) {
            throw EntityNotFoundException("patient not found: ${command.patientId}")
        }

        val pageSize = command.pageRequest.pageSize
        val limit = Limit.of(pageSize + 1)

        val rawCursor = command.pageRequest.cursor
        val rows: List<EncounterEntity> = if (rawCursor == null) {
            encounterRepository.findFirstPage(tenant.id, patient.id, limit)
        } else {
            val map = CursorCodec.decodeMap(rawCursor)
            val cursor = TimeCursor.fromMap(map = map, expectedKey = CURSOR_K)
            encounterRepository.findAfter(
                tenantId = tenant.id,
                patientId = patient.id,
                ts = cursor.ts,
                id = cursor.id,
                limit = limit,
            )
        }

        return PageResponse.fromFetchedPlusOne(
            fetchedPlusOne = rows.map { EncounterSnapshot.from(it) },
            pageSize = pageSize,
        ) { last ->
            TimeCursor(
                k = CURSOR_K,
                ts = last.createdAt,
                id = last.id,
                ascending = false,
            )
        }
    }

    private companion object {
        /** Cursor schema discriminator; bump to `.v2` if sort axis or fields change. */
        const val CURSOR_K: String = "clinical.encounter.v1"
    }
}
