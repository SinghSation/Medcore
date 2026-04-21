package com.medcore.tenancy.persistence

import com.medcore.platform.tenancy.TenantStatus
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
 * JPA entity for `tenancy.tenant`.
 *
 * Mirrors the discipline of `IdentityUserEntity`: regular mutable class
 * (not a data class), explicit `@Version` for optimistic concurrency,
 * audit timestamps written by the service layer against an injected
 * UTC clock.
 *
 * Never handed across the module boundary — callers receive a
 * [com.medcore.tenancy.service.TenantSummary] instead.
 */
@Entity
@Table(name = "tenant", schema = "tenancy")
class TenantEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID,

    @Column(name = "slug", nullable = false, updatable = false)
    var slug: String,

    @Column(name = "display_name", nullable = false)
    var displayName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: TenantStatus,

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
        slug = "",
        displayName = "",
        status = TenantStatus.ACTIVE,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
