package com.medcore.platform.security

import java.time.Instant

/**
 * Port the security layer uses to turn a validated OIDC token into a
 * Medcore-internal principal. Implemented in the identity module by
 * [com.medcore.identity.IdentityProvisioningService].
 *
 * This SPI keeps the security layer free of knowledge about identity's JPA
 * entities / repository shape — it only knows "someone can resolve a
 * principal from a token-derived claim bundle." Module boundaries remain
 * unidirectional: feature modules depend on platform, not the other way
 * around (Charter §4, Rule 00).
 */
interface PrincipalResolver {
    fun resolve(command: PrincipalResolutionCommand): MedcorePrincipal
}

/**
 * Standard OIDC claim projection handed to [PrincipalResolver]. Fields are
 * explicit and minimal so a PR reviewer can see every cross-boundary value
 * at a glance (Rule 01, Rule 02).
 *
 * The raw JWT is deliberately NOT included. The converter extracts every
 * field the identity module needs and passes typed values only, so the
 * token object never crosses the platform → feature boundary.
 */
data class PrincipalResolutionCommand(
    val issuerSubject: IssuerSubject,
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String?,
    val preferredUsername: String?,
    val issuedAt: Instant?,
    val expiresAt: Instant?,
)
