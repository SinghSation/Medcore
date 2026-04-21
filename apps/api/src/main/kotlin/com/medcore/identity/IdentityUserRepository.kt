package com.medcore.identity

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Package-private to the identity module by convention: all cross-module
 * access funnels through [IdentityProvisioningService] (Rule 00 — no
 * cross-module table access).
 */
interface IdentityUserRepository : JpaRepository<IdentityUserEntity, UUID> {
    fun findByIssuerAndSubject(issuer: String, subject: String): IdentityUserEntity?
}
