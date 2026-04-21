package com.medcore.tenancy.persistence

import com.medcore.platform.tenancy.MembershipRole
import com.medcore.platform.tenancy.MembershipStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for `tenancy.tenant_membership`.
 *
 * Intentionally stores `tenant_id` and `user_id` as raw UUIDs instead of
 * modelling a `@ManyToOne` association:
 *
 * - `tenant_id` could be a JPA association (same module), but exposing a
 *   lazy `TenantEntity` reference invites open-in-view pitfalls and tempts
 *   callers to traverse into the tenant graph; explicit UUIDs keep the
 *   boundary sharp and let queries stay flat.
 * - `user_id` MUST NOT reference `identity.user` via a cross-module
 *   foreign key (ADR-001 §2). The UUID is opaque; the identity module
 *   owns uniqueness and existence on its side.
 *
 * Never handed across the module boundary.
 */
@Entity
@Table(name = "tenant_membership", schema = "tenancy")
class TenantMembershipEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID,

    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID,

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    var role: MembershipRole,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: MembershipStatus,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Version
    @Column(name = "row_version", nullable = false)
    var rowVersion: Long = 0,
) {
    @Suppress("unused") // JPA no-arg constructor.
    protected constructor() : this(
        id = UUID(0L, 0L),
        tenantId = UUID(0L, 0L),
        userId = UUID(0L, 0L),
        role = MembershipRole.MEMBER,
        status = MembershipStatus.ACTIVE,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
