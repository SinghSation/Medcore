package com.medcore.tenancy.write

import com.medcore.platform.tenancy.TenantStatus
import java.util.UUID

/**
 * Post-write projection of a [com.medcore.tenancy.persistence.TenantEntity]
 * (Phase 3J.2). Internal result type for the write path — never
 * crosses the HTTP boundary (the controller converts it to
 * [com.medcore.tenancy.api.TenantSummaryResponse]).
 *
 * ### Why a dedicated type
 *
 * Returning the JPA entity would leak `@Version`, JPA proxies, and
 * the rest of the persistence concerns across the write pipeline.
 * Returning a `TenantSummary` (the read-side projection) would
 * lose one signal the write path specifically needs: [changed].
 *
 * ### The [changed] flag
 *
 * Distinguishes "UPDATE fired, row modified, audit should emit"
 * from "no-op: caller submitted the same displayName that is
 * already persisted." The handler checks equality before touching
 * the entity and short-circuits; the auditor reads [changed] and
 * suppresses the `TENANCY_TENANT_UPDATED` audit event for no-ops.
 *
 * Benefits: avoids phantom `@Version` bumps, avoids audit-log noise
 * on idempotent retries, preserves the "every persisted change
 * emits an audit row" invariant (the negation holds: no row
 * change, no audit).
 */
data class TenantSnapshot(
    val id: UUID,
    val slug: String,
    val displayName: String,
    val status: TenantStatus,
    val changed: Boolean,
)
