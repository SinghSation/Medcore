package com.medcore.tenancy.write

import com.medcore.platform.tenancy.MembershipRole
import com.medcore.platform.tenancy.MembershipStatus
import com.medcore.platform.tenancy.TenantStatus
import java.util.UUID

/**
 * Post-write projection of a [com.medcore.tenancy.persistence.TenantMembershipEntity]
 * (Phase 3J.3). Internal result type for the write path — never
 * crosses the HTTP boundary; the controller converts to
 * [com.medcore.tenancy.api.MembershipResponse].
 *
 * Carries tenant denormalisations ([tenantSlug], [tenantDisplayName],
 * [tenantStatus]) so the controller can build the full
 * `MembershipResponse` (which embeds `TenantSummaryResponse`)
 * without a second DB read.
 *
 * Phase 3J.3 always creates an ACTIVE membership — email-token
 * invitation flow (PENDING lifecycle) is a future slice. The
 * `status` field is part of the snapshot shape so the PENDING
 * path can reuse the envelope when it lands.
 */
data class MembershipSnapshot(
    val id: UUID,
    val userId: UUID,
    val role: MembershipRole,
    val status: MembershipStatus,
    val tenantId: UUID,
    val tenantSlug: String,
    val tenantDisplayName: String,
    val tenantStatus: TenantStatus,
)
