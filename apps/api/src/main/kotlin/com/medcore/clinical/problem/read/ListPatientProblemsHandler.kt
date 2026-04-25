package com.medcore.clinical.problem.read

import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.clinical.problem.persistence.ProblemEntity
import com.medcore.clinical.problem.persistence.ProblemRepository
import com.medcore.clinical.problem.write.ProblemSnapshot
import com.medcore.platform.read.pagination.BucketedCursor
import com.medcore.platform.read.pagination.CursorCodec
import com.medcore.platform.read.pagination.PageResponse
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Component

/**
 * Handler for [ListPatientProblemsCommand] (Phase 4E.2,
 * paginated as of platform-pagination chunk E, ADR-009).
 *
 * Mirrors [com.medcore.clinical.allergy.read.ListPatientAllergiesHandler]
 * — same `BucketedCursor` shape; only the resource type and
 * `k` discriminator differ. The cursor walks across all four
 * status buckets (0=ACTIVE, 1=INACTIVE, 2=RESOLVED,
 * 3=ENTERED_IN_ERROR) — RESOLVED is the slot that allergies
 * leave empty, here it's populated.
 *
 * **RESOLVED ≠ INACTIVE** — the cursor's `bucket` field encodes
 * the distinction across pages: a page-1 last-row in INACTIVE
 * (bucket=1) advances to RESOLVED (bucket=2) on page 2; a
 * page-1 last-row in RESOLVED (bucket=2) advances to
 * ENTERED_IN_ERROR (bucket=3). Without the bucket field, the
 * cursor would conflate these transitions and re-disclose rows.
 *
 * Runs inside [com.medcore.platform.read.ReadGate]'s transaction
 * with both RLS GUCs set by `PhiRlsTxHook`.
 */
@Component
class ListPatientProblemsHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
    private val problemRepository: ProblemRepository,
) {

    fun handle(
        command: ListPatientProblemsCommand,
        @Suppress("UNUSED_PARAMETER") context: WriteContext,
    ): ListPatientProblemsResult {
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
        val rows: List<ProblemEntity> = if (rawCursor == null) {
            problemRepository.findFirstPage(tenant.id, patient.id, limit)
        } else {
            val map = CursorCodec.decodeMap(rawCursor)
            val cursor = BucketedCursor.fromMap(map, expectedKey = CURSOR_K)
            problemRepository.findAfter(
                tenantId = tenant.id,
                patientId = patient.id,
                bucket = cursor.bucket,
                ts = cursor.ts,
                id = cursor.id,
                limit = limit,
            )
        }

        return PageResponse.fromFetchedPlusOne(
            fetchedPlusOne = rows.map { ProblemSnapshot.from(it) },
            pageSize = pageSize,
        ) { last ->
            BucketedCursor(
                k = CURSOR_K,
                bucket = last.status.priority,
                ts = last.createdAt,
                id = last.id,
            )
        }
    }

    private companion object {
        /** Cursor schema discriminator; bump to `.v2` if the sort axis or fields change. */
        const val CURSOR_K: String = "clinical.problem.v1"
    }
}
