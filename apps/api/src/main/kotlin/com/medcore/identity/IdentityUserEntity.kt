package com.medcore.identity

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
 * JPA entity for `identity.user`.
 *
 * Intentionally NOT a Kotlin data class: data classes' generated
 * equals/hashCode and the kotlin-jpa plugin's synthetic no-arg constructor
 * are a footgun for lazy loading, equality across detached instances, and
 * optimistic concurrency. A mutable class with `var` fields is the
 * conventional Spring Data JPA shape and is the project-level rule.
 *
 * Audit columns [createdAt] and [updatedAt] are maintained by the
 * provisioning service at write time (clock injected) rather than by JPA
 * lifecycle callbacks, so tests are deterministic (Rule 05).
 * `created_by` / `updated_by` columns are intentionally NOT shipped in this
 * slice — a request-scoped actor context does not yet exist, and shipping
 * always-null columns with no writer is worse than adding them later in an
 * additive migration when the writer lands (ADR-001 §2 audit-columns
 * directive; tracked for Phase 3B once tenancy + request context arrive).
 */
@Entity
@Table(name = "`user`", schema = "identity")
class IdentityUserEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID,

    @Column(name = "issuer", nullable = false, updatable = false)
    var issuer: String,

    @Column(name = "subject", nullable = false, updatable = false)
    var subject: String,

    @Column(name = "email")
    var email: String?,

    @Column(name = "email_verified", nullable = false)
    var emailVerified: Boolean,

    @Column(name = "display_name")
    var displayName: String?,

    @Column(name = "preferred_username")
    var preferredUsername: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: IdentityUserStatus,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Version
    @Column(name = "row_version", nullable = false)
    var rowVersion: Long = 0,
) {
    @Suppress("unused") // No-arg constructor required by JPA; kept explicit for clarity.
    protected constructor() : this(
        id = UUID(0L, 0L),
        issuer = "",
        subject = "",
        email = null,
        emailVerified = false,
        displayName = null,
        preferredUsername = null,
        status = IdentityUserStatus.ACTIVE,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}

/**
 * Lifecycle states for an identity record.
 *
 * `ACTIVE` is the only valid state today; `DISABLED` and `DELETED` are
 * reserved so future admin flows can land as additive migrations without a
 * schema rewrite. Attempting to authenticate as anything other than
 * `ACTIVE` will be rejected once those flows exist (tracked for Phase 3B/3D).
 */
enum class IdentityUserStatus {
    ACTIVE,
    DISABLED,
    DELETED,
}
