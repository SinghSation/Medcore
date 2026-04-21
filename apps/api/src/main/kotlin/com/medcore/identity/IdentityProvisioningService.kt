package com.medcore.identity

import com.medcore.platform.security.IssuerSubject
import com.medcore.platform.security.MedcorePrincipal
import com.medcore.platform.security.PrincipalResolutionCommand
import com.medcore.platform.security.PrincipalResolver
import com.medcore.platform.security.PrincipalStatus
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Identity module entry point.
 *
 * - Implements [PrincipalResolver] so the platform security layer never
 *   reaches into this module's JPA entity / repository (Rule 00, Charter §4).
 * - Owns just-in-time provisioning of `identity.user` rows keyed on
 *   `(issuer, subject)`. Idempotent: a second request for the same external
 *   identity returns the existing row, never inserts twice (ADR-002 §2,
 *   Acceptance Criteria).
 *
 * TODO(phase-3C): emit `identity.user.provisioned` and
 * `identity.user.login.success` audit events here. Deferred with ADR-003
 * (Audit v1) as explicit scope.
 *
 * TODO(phase-3A.4): concurrent-first-login for the same (issuer, subject) can
 * race the unique constraint. The DB rejects the duplicate and the request
 * fails cleanly; the client retries and finds the existing row. A retry loop
 * with `Propagation.REQUIRES_NEW` will land when request-scoped retry
 * infrastructure arrives; deliberately out-of-scope for this slice.
 */
@Service
class IdentityProvisioningService(
    private val userRepository: IdentityUserRepository,
    private val clock: Clock,
) : PrincipalResolver {

    @Transactional
    override fun resolve(command: PrincipalResolutionCommand): MedcorePrincipal {
        val now = Instant.now(clock)
        val entity = userRepository.findByIssuerAndSubject(
            command.issuerSubject.issuer,
            command.issuerSubject.subject,
        ).let { existing ->
            if (existing != null) {
                applyClaimRefresh(existing, command, now)
                existing
            } else {
                val fresh = IdentityUserEntity(
                    id = UUID.randomUUID(),
                    issuer = command.issuerSubject.issuer,
                    subject = command.issuerSubject.subject,
                    email = command.email,
                    emailVerified = command.emailVerified,
                    displayName = command.displayName,
                    preferredUsername = command.preferredUsername,
                    status = IdentityUserStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                )
                userRepository.saveAndFlush(fresh)
            }
        }

        return MedcorePrincipal(
            userId = entity.id,
            issuerSubject = IssuerSubject(issuer = entity.issuer, subject = entity.subject),
            email = entity.email,
            emailVerified = entity.emailVerified,
            displayName = entity.displayName,
            preferredUsername = entity.preferredUsername,
            status = entity.status.toPrincipalStatus(),
            issuedAt = command.issuedAt,
            expiresAt = command.expiresAt,
        )
    }

    private fun applyClaimRefresh(
        entity: IdentityUserEntity,
        command: PrincipalResolutionCommand,
        now: Instant,
    ) {
        var changed = false
        if (entity.email != command.email) {
            entity.email = command.email
            changed = true
        }
        if (entity.emailVerified != command.emailVerified) {
            entity.emailVerified = command.emailVerified
            changed = true
        }
        if (entity.displayName != command.displayName) {
            entity.displayName = command.displayName
            changed = true
        }
        if (entity.preferredUsername != command.preferredUsername) {
            entity.preferredUsername = command.preferredUsername
            changed = true
        }
        if (changed) {
            entity.updatedAt = now
        }
    }

    private fun IdentityUserStatus.toPrincipalStatus(): PrincipalStatus =
        when (this) {
            IdentityUserStatus.ACTIVE -> PrincipalStatus.ACTIVE
            IdentityUserStatus.DISABLED -> PrincipalStatus.DISABLED
            IdentityUserStatus.DELETED -> PrincipalStatus.DELETED
        }
}
