package com.medcore.clinical.patient.write

import com.medcore.clinical.patient.persistence.PatientEntity
import com.medcore.clinical.patient.persistence.PatientRepository
import com.medcore.platform.write.WriteConflictException
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import java.time.Instant
import org.springframework.stereotype.Component

/**
 * Handler for [UpdatePatientDemographicsCommand] (Phase 4A.2).
 *
 * Runs inside the `WriteGate`-owned transaction with RLS GUCs
 * set by `PhiRlsTxHook`. Responsibilities:
 *
 * 1. Load the tenant by slug (RLS-gated).
 * 2. Load the target patient by id. 404 if missing OR if it
 *    belongs to a different tenant (cross-tenant id probing
 *    defence — identical behaviour to 3J.N's membership check).
 * 3. **`If-Match` / `row_version` precondition** — compare the
 *    caller-supplied `expectedRowVersion` to the loaded row's
 *    `row_version`. Mismatch → throw
 *    [WriteConflictException]("stale_row") → 409
 *    `resource.conflict` with `details.reason = stale_row`.
 * 4. Apply every patchable field — [Patchable.Set] writes the
 *    value, [Patchable.Clear] writes NULL (validator already
 *    refused `Clear` on required columns), [Patchable.Absent]
 *    leaves the column unchanged.
 * 5. Detect a genuine no-op — if every `Set` / `Clear` resolves
 *    to the existing value, return `changed = false`. The
 *    auditor suppresses emission. "Every persisted change emits
 *    an audit row" holds.
 * 6. Stamp `updated_at`, `updated_by`, and `row_version` via JPA
 *    `@Version` at flush.
 *
 * ### Why we compare `expectedRowVersion` manually (belt + braces
 * with `@Version`)
 *
 * JPA's `@Version` ultimately enforces optimistic locking at
 * flush time, throwing `ObjectOptimisticLockingFailureException`
 * → 409 `resource.conflict`. We ALSO check `expectedRowVersion`
 * in the handler BEFORE mutating so:
 *   - Stale-If-Match surfaces BEFORE any DB mutation — cleaner
 *     rollback, no JPA internal-state churn.
 *   - The error carries our explicit `details.reason = stale_row`
 *     slug (from [WriteConflictException]) — not the generic
 *     optimistic-lock message.
 *   - Tests can target a deterministic code path.
 *
 * If someone bypasses this check (hypothetically), `@Version`
 * still catches it at flush.
 */
@Component
class UpdatePatientDemographicsHandler(
    private val tenantRepository: TenantRepository,
    private val patientRepository: PatientRepository,
    private val clock: Clock,
) {

    fun handle(
        command: UpdatePatientDemographicsCommand,
        context: WriteContext,
    ): UpdatePatientDemographicsSnapshot {
        val tenant = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException("tenant not found: ${command.slug}")

        val patient = patientRepository.findById(command.patientId).orElse(null)
            ?: throw EntityNotFoundException("patient not found: ${command.patientId}")
        if (patient.tenantId != tenant.id) {
            // Cross-tenant id probe — identical response to unknown id
            // so existence does not leak.
            throw EntityNotFoundException("patient not found: ${command.patientId}")
        }

        if (patient.rowVersion != command.expectedRowVersion) {
            throw WriteConflictException("stale_row")
        }

        val changed = applyPatches(patient, command)
        if (!changed) {
            return UpdatePatientDemographicsSnapshot(
                snapshot = toSnapshot(patient),
                changed = false,
                changedFields = emptySet(),
            )
        }

        patient.updatedAt = Instant.now(clock)
        patient.updatedBy = context.principal.userId
        // row_version is bumped by JPA @Version on flush.
        val saved = patientRepository.saveAndFlush(patient)

        return UpdatePatientDemographicsSnapshot(
            snapshot = toSnapshot(saved),
            changed = true,
            changedFields = command.changingFieldNames(),
        )
    }

    /**
     * Applies patchable values to [entity] and returns `true` if
     * any column actually changed. No-op detection runs here —
     * equal-value assignments do NOT count as changes so the
     * auditor correctly suppresses emission.
     */
    private fun applyPatches(
        entity: PatientEntity,
        command: UpdatePatientDemographicsCommand,
    ): Boolean {
        var changed = false

        changed = apply(command.nameGiven, entity.nameGiven,
            set = { entity.nameGiven = it },
            clear = { /* refused by validator */ },
        ) || changed

        changed = apply(command.nameFamily, entity.nameFamily,
            set = { entity.nameFamily = it },
            clear = { /* refused by validator */ },
        ) || changed

        changed = apply(command.nameMiddle, entity.nameMiddle,
            set = { entity.nameMiddle = it },
            clear = { entity.nameMiddle = null },
        ) || changed

        changed = apply(command.nameSuffix, entity.nameSuffix,
            set = { entity.nameSuffix = it },
            clear = { entity.nameSuffix = null },
        ) || changed

        changed = apply(command.namePrefix, entity.namePrefix,
            set = { entity.namePrefix = it },
            clear = { entity.namePrefix = null },
        ) || changed

        changed = apply(command.preferredName, entity.preferredName,
            set = { entity.preferredName = it },
            clear = { entity.preferredName = null },
        ) || changed

        changed = apply(command.birthDate, entity.birthDate,
            set = { entity.birthDate = it },
            clear = { /* refused by validator */ },
        ) || changed

        changed = apply(command.administrativeSex, entity.administrativeSex,
            set = { entity.administrativeSex = it },
            clear = { /* refused by validator */ },
        ) || changed

        changed = apply(command.sexAssignedAtBirth, entity.sexAssignedAtBirth,
            set = { entity.sexAssignedAtBirth = it },
            clear = { entity.sexAssignedAtBirth = null },
        ) || changed

        changed = apply(command.genderIdentityCode, entity.genderIdentityCode,
            set = { entity.genderIdentityCode = it },
            clear = { entity.genderIdentityCode = null },
        ) || changed

        changed = apply(command.preferredLanguage, entity.preferredLanguage,
            set = { entity.preferredLanguage = it },
            clear = { entity.preferredLanguage = null },
        ) || changed

        return changed
    }

    private inline fun <T> apply(
        patch: Patchable<T>,
        current: T?,
        set: (T) -> Unit,
        clear: () -> Unit,
    ): Boolean = when (patch) {
        Patchable.Absent -> false
        Patchable.Clear -> {
            if (current != null) {
                clear()
                true
            } else false
        }
        is Patchable.Set<T> -> {
            if (patch.value != current) {
                set(patch.value)
                true
            } else false
        }
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

/**
 * Handler output for demographics-update commands. Carries the
 * post-change [snapshot] for the response body, the [changed] flag
 * for no-op suppression, and the [changedFields] set for the
 * auditor's `fields:` reason slug.
 */
data class UpdatePatientDemographicsSnapshot(
    val snapshot: PatientSnapshot,
    val changed: Boolean,
    val changedFields: Set<String>,
)
