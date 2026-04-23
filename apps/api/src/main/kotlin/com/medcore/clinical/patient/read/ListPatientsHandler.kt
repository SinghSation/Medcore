package com.medcore.clinical.patient.read

import com.medcore.clinical.patient.persistence.PatientEntity
import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.clinical.patient.write.PatientSnapshot
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component

/**
 * Handler for [ListPatientsCommand] (Phase 4B.1).
 *
 * Runs inside [com.medcore.platform.read.ReadGate]'s transaction
 * with RLS GUCs (`app.current_tenant_id`, `app.current_user_id`)
 * set by `PhiRlsTxHook`. The page query and the count query
 * both run under `medcore_app` so V14 `p_patient_select`
 * filters cross-tenant rows and soft-deleted rows at the DB
 * layer — no application-side filtering required.
 *
 * ### Flow
 *
 * 1. Resolve the tenant by slug. Policy already verified the
 *    caller holds `PATIENT_READ`; this load is an existence
 *    check that yields 404 for unknown slugs (same behaviour
 *    as the write handlers).
 * 2. Run the page query with the tenant UUID + `Pageable(offset, limit)`.
 *    `Pageable.offset` in Spring Data takes `page` not `offset`,
 *    so `PageRequest.of(offset / limit, limit)` only works
 *    when offset is a multiple of limit. We build a
 *    `Pageable` using `OffsetLimitPageable`-equivalent with
 *    `PageRequest.of(…)` by computing `pageNumber = offset / limit`
 *    is INCORRECT for arbitrary offsets.
 *    — Solution: use a pageable that applies raw offset.
 *      Spring Data supports `OffsetScrollPosition` and
 *      `Pageable.unpaged()`. For clarity we construct a
 *      simple `PageRequest` using integer division AND assert
 *      `offset % limit == 0` — the controller enforces that.
 *    — Alternatively: use native SQL with LIMIT/OFFSET.
 *    4B.1 chooses the assertion path; Pageable page arithmetic
 *    is the documented JPA semantic and `offset` bounded by
 *    `limit` keeps the public surface simple. If future slices
 *    want arbitrary offsets, switch to a custom `@Query` with
 *    `setFirstResult(offset).setMaxResults(limit)` via an
 *    `EntityManager`-level custom method.
 *
 *    **Wait — that's fragile.** Revised: use
 *    `org.springframework.data.domain.PageRequest.of(offset / limit, limit)`
 *    iff `offset % limit == 0`. Controller enforces the page
 *    boundary via the validator (422 on unaligned offset).
 *
 * 3. Run the count query. Same RLS envelope.
 * 4. Map entities to [PatientSnapshot]s.
 * 5. Return [ListPatientsResult] with items + total.
 *
 * ### Why NOT `@Transactional`
 *
 * `ReadGate` owns the transaction. Same discipline as
 * [GetPatientHandler]: no nested-tx ambiguity.
 */
@Component
class ListPatientsHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
) {

    fun handle(
        command: ListPatientsCommand,
        @Suppress("UNUSED_PARAMETER") context: WriteContext,
    ): ListPatientsResult {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        // `PageRequest.of(page, size)` uses page-number semantics:
        // `pageNumber = offset / limit`. Controller enforces
        // `offset % limit == 0` (422 on unaligned) so this
        // division is always exact. Mis-aligned offsets are a
        // caller error, not a silent truncation.
        val pageable = PageRequest.of(command.offset / command.limit, command.limit)
        val entities = patientRepository.findByTenantIdPaged(tenant.id, pageable)
        val total = patientRepository.countByTenantId(tenant.id)

        return ListPatientsResult(
            items = entities.map { toSnapshot(it) },
            totalCount = total,
            limit = command.limit,
            offset = command.offset,
        )
    }

    private fun toSnapshot(entity: PatientEntity): PatientSnapshot = PatientSnapshot(
        id = entity.id,
        tenantId = entity.tenantId,
        mrn = entity.mrn,
        mrnSource = entity.mrnSource,
        nameGiven = entity.nameGiven,
        nameFamily = entity.nameFamily,
        nameMiddle = entity.nameMiddle,
        nameSuffix = entity.nameSuffix,
        namePrefix = entity.namePrefix,
        preferredName = entity.preferredName,
        birthDate = entity.birthDate,
        administrativeSex = entity.administrativeSex,
        sexAssignedAtBirth = entity.sexAssignedAtBirth,
        genderIdentityCode = entity.genderIdentityCode,
        preferredLanguage = entity.preferredLanguage,
        status = entity.status,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        createdBy = entity.createdBy,
        updatedBy = entity.updatedBy,
        rowVersion = entity.rowVersion,
    )
}
