package com.medcore.platform.tenancy

import java.util.UUID

/**
 * Narrow port the platform layer uses to resolve a caller's tenant-scoped
 * access. Implemented in the tenancy module
 * ([com.medcore.tenancy.service.TenancyService]).
 *
 * Platform-owned, self-contained: this file imports nothing from the
 * tenancy module and exposes only platform types. The feature module
 * depends on the platform port; the platform does not learn tenancy's
 * internals (Charter §4, Rule 00 — one-way boundary).
 *
 * The enums below are the authoritative cross-boundary contract for
 * tenant / membership lifecycle and role naming. Tenancy persistence
 * stores their string names (`@Enumerated(EnumType.STRING)`); the
 * database CHECK constraints in V5 / V6 match these values exactly. If a
 * new value is introduced it must ship (a) here, (b) in the tenancy
 * migrations (CHECK), and (c) in the OpenAPI enum declarations, together.
 */
interface TenantMembershipLookup {
    fun resolve(userId: UUID, slug: String): ResolvedMembership?
}

data class ResolvedMembership(
    val tenantId: UUID,
    val tenantSlug: String,
    val tenantDisplayName: String,
    val tenantStatus: TenantStatus,
    val userId: UUID,
    val membershipId: UUID,
    val role: MembershipRole,
    val status: MembershipStatus,
) {
    val isActive: Boolean
        get() = status == MembershipStatus.ACTIVE && tenantStatus == TenantStatus.ACTIVE
}

/** Lifecycle state of a tenant. Only [ACTIVE] accepts membership traffic. */
enum class TenantStatus {
    ACTIVE,
    SUSPENDED,
    ARCHIVED,
}

/**
 * Lifecycle state of a single user's membership in a tenant. Only
 * [ACTIVE] grants access; [SUSPENDED] denies reversibly, [REVOKED]
 * denies permanently. No route currently distinguishes the latter two —
 * both yield the same 403 response shape (Rule 01: deny-by-default).
 */
enum class MembershipStatus {
    ACTIVE,
    SUSPENDED,
    REVOKED,
}

/**
 * Membership role. Used for response payloads only at 3B.1; no
 * authority/ABAC mapping lands in this slice (intentionally deferred —
 * policy engine is later). Role values flow through the wire as the
 * uppercase enum name.
 */
enum class MembershipRole {
    OWNER,
    ADMIN,
    MEMBER,
}
