package com.medcore.platform.security.phi

import java.util.UUID

/**
 * Immutable snapshot of the (userId, tenantId) pair required for
 * every PHI-bearing data access (Phase 4A.0).
 *
 * ### Why a dedicated type
 *
 * [userId] + [tenantId] together form the "PHI execution context"
 * — the minimum information needed to set the RLS GUCs that gate
 * every clinical-schema read/write. Wrapping them in a data class
 * (rather than passing two UUIDs around) makes the invariant
 * explicit: **both fields are required; partial context is
 * structurally impossible.**
 *
 * A future slice that wants to carry additional PHI-execution
 * state (e.g., a purpose-of-use claim for break-glass audit)
 * extends this class with additional required fields; existing
 * callers continue to work because the original two fields remain.
 *
 * ### Invariant — both fields present
 *
 * Per 4A.0 refinement #4: partial context is rejected at
 * construction. [PhiRequestContextFilter] either populates both
 * fields and sets the holder, or populates neither (filter is a
 * no-op for non-PHI routes). [PhiSessionContext.applyFromRequest]
 * reads this class — its fields are non-nullable so no partial-
 * context silent-acceptance path exists.
 */
data class PhiRequestContext(
    val userId: UUID,
    val tenantId: UUID,
)
