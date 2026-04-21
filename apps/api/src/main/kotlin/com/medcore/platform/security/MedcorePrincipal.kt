package com.medcore.platform.security

import java.time.Instant
import java.util.UUID
import org.springframework.security.core.AuthenticatedPrincipal

/**
 * The internal principal shape for Medcore.
 *
 * Resource-server JWT validation yields this object as the authenticated
 * principal. It separates the **external** identity (issuer + subject from
 * the IdP) from the **internal** identity ([userId], the `identity.user.id`
 * row), so downstream code can audit, authorize, and log without re-parsing
 * the JWT.
 *
 * Intentionally NOT a Kotlin `data class`:
 *
 * - Generated `equals` / `hashCode` over every claim field would conflate
 *   "same user" with "same claim snapshot," which is wrong for security
 *   principals. Equality is by [userId] only — the stable internal handle.
 * - Generated `toString` would dump email, display name, and preferred
 *   username into any log line that prints the principal. [toString] here
 *   emits only [userId] so no PII can leak into logs (Rule 01, Rule 06).
 *
 * This class deliberately does NOT carry the raw JWT. The converter extracts
 * every field this principal needs (including [issuedAt] / [expiresAt]) so
 * the token object never escapes the authentication boundary.
 */
class MedcorePrincipal(
    val userId: UUID,
    val issuerSubject: IssuerSubject,
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String?,
    val preferredUsername: String?,
    val status: PrincipalStatus,
    val issuedAt: Instant?,
    val expiresAt: Instant?,
) : AuthenticatedPrincipal {
    override fun getName(): String = userId.toString()

    override fun equals(other: Any?): Boolean =
        this === other || (other is MedcorePrincipal && other.userId == userId)

    override fun hashCode(): Int = userId.hashCode()

    override fun toString(): String = "MedcorePrincipal(userId=$userId)"
}

/**
 * An (issuer, subject) pair — the canonical external identity key the IdP
 * presents. Combined with the `identity.user` table's unique constraint,
 * this drives JIT provisioning idempotency (ADR-002 §2).
 */
data class IssuerSubject(
    val issuer: String,
    val subject: String,
)

/**
 * Lifecycle status projected onto the principal. The identity module's
 * internal enum maps onto this; platform code reads only these values so
 * it stays decoupled from identity's internal representation.
 */
enum class PrincipalStatus {
    ACTIVE,
    DISABLED,
    DELETED,
}
