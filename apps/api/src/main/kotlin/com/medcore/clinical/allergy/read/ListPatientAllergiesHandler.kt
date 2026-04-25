package com.medcore.clinical.allergy.read

import com.medcore.clinical.allergy.persistence.AllergyEntity
import com.medcore.clinical.allergy.persistence.AllergyRepository
import com.medcore.clinical.allergy.write.AllergySnapshot
import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.platform.read.pagination.BucketedCursor
import com.medcore.platform.read.pagination.CursorCodec
import com.medcore.platform.read.pagination.PageResponse
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Component

/**
 * Handler for [ListPatientAllergiesCommand] (Phase 4E.1,
 * paginated as of platform-pagination chunk D, ADR-009).
 *
 * **First [BucketedCursor] adopter.** The cursor encodes the
 * full sort tuple `(status_priority, createdAt, id)` so cross-
 * bucket walks resume at the correct bucket boundary.
 *
 * Runs inside [com.medcore.platform.read.ReadGate]'s
 * transaction with both RLS GUCs set by `PhiRlsTxHook`.
 *
 * ### Flow
 *
 * 1. Resolve tenant by slug.
 * 2. Load patient; verify `patient.tenantId == tenant.id`
 *    (cross-tenant probe → 404, no leak).
 * 3. Decode optional cursor:
 *    - `null` → first page (ACTIVE rows surface here per
 *      ADR-009 §2.5 ACTIVE-first guarantee).
 *    - non-null → [BucketedCursor.fromMap] with key
 *      `"clinical.allergy.v1"`. Malformed → 422.
 * 4. Fetch `pageSize + 1` rows; first page or cursor walk.
 * 5. Hand to [PageResponse.fromFetchedPlusOne]; cursor builder
 *    encodes the LAST trimmed row's `(status.priority, createdAt, id)`.
 *
 * ### Cursor walk vs first page
 *
 * The first-page query uses an `ORDER BY` only — no `WHERE` on
 * the cursor tuple. The cursor walk adds a 3-clause OR:
 *   `bucket > b_last`              (next bucket)
 *   `bucket = b_last AND ts < ts_last`     (same bucket, older)
 *   `bucket = b_last AND ts = ts_last AND id < id_last`  (tiebreak)
 *
 * The repository encapsulates both queries.
 */
@Component
class ListPatientAllergiesHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
    private val allergyRepository: AllergyRepository,
) {

    fun handle(
        command: ListPatientAllergiesCommand,
        @Suppress("UNUSED_PARAMETER") context: WriteContext,
    ): ListPatientAllergiesResult {
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
        val rows: List<AllergyEntity> = if (rawCursor == null) {
            allergyRepository.findFirstPage(tenant.id, patient.id, limit)
        } else {
            val map = CursorCodec.decodeMap(rawCursor)
            val cursor = BucketedCursor.fromMap(map, expectedKey = CURSOR_K)
            allergyRepository.findAfter(
                tenantId = tenant.id,
                patientId = patient.id,
                bucket = cursor.bucket,
                ts = cursor.ts,
                id = cursor.id,
                limit = limit,
            )
        }

        return PageResponse.fromFetchedPlusOne(
            fetchedPlusOne = rows.map { AllergySnapshot.from(it) },
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
        /** Cursor schema discriminator; bump to `.v2` if sort axis or fields change. */
        const val CURSOR_K: String = "clinical.allergy.v1"
    }
}
