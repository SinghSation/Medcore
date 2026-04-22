package com.medcore.tenancy.write

import com.medcore.tenancy.persistence.TenantRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.stereotype.Component

/**
 * Handler for [UpdateTenantDisplayNameCommand] (Phase 3J.2).
 *
 * Runs inside the [com.medcore.platform.write.WriteGate]-owned
 * transaction, AFTER [UpdateTenantDisplayNamePolicy] has granted
 * the mutation. The V12 RLS write policy on `tenancy.tenant` is the
 * DB-level backstop: even if a future caller bypasses this handler
 * and issues raw UPDATEs, Postgres refuses when the caller lacks an
 * OWNER/ADMIN membership.
 *
 * ### No-op optimisation (ADR-007 §2.4 carry-forward accepted)
 *
 * When the caller submits the same `displayName` that is already
 * persisted, the handler returns [TenantSnapshot] with
 * `changed = false` WITHOUT modifying the entity. JPA's dirty-
 * checking would catch the equality and skip the UPDATE anyway;
 * making it explicit communicates intent to future readers and
 * guarantees the audit auditor can suppress the event deterministically.
 */
@Component
class UpdateTenantDisplayNameHandler(
    private val tenantRepository: TenantRepository,
) {

    fun handle(command: UpdateTenantDisplayNameCommand): TenantSnapshot {
        val entity = tenantRepository.findBySlug(command.slug)
            ?: throw EntityNotFoundException(
                // Message is logged, never echoed to the HTTP response
                // (Phase 3G `GlobalExceptionHandler.onNotFound` emits a
                // fixed string). The path to this branch is mostly
                // unreachable in practice — the policy rejects unknown
                // slugs upstream with `NOT_A_MEMBER` — but we fail
                // explicitly rather than NPE for defence in depth.
                "tenant not found: ${command.slug}",
            )
        if (entity.displayName == command.displayName) {
            return TenantSnapshot(
                id = entity.id,
                slug = entity.slug,
                displayName = entity.displayName,
                status = entity.status,
                changed = false,
            )
        }
        entity.displayName = command.displayName
        // JPA flush at tx boundary will bump `@Version` (row_version).
        // `save()` returns the managed entity; we read from it rather
        // than `entity` to stay behind the persistence contract.
        val saved = tenantRepository.save(entity)
        return TenantSnapshot(
            id = saved.id,
            slug = saved.slug,
            displayName = saved.displayName,
            status = saved.status,
            changed = true,
        )
    }
}
