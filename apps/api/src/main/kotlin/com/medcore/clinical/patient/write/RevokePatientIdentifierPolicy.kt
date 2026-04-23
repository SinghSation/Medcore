package com.medcore.clinical.patient.write

import com.medcore.platform.security.AuthorityResolution
import com.medcore.platform.security.AuthorityResolver
import com.medcore.platform.security.MedcoreAuthority
import com.medcore.platform.write.AuthzPolicy
import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import org.springframework.stereotype.Component

/**
 * Authorization policy for [RevokePatientIdentifierCommand]
 * (Phase 4A.3).
 *
 * Mirrors [AddPatientIdentifierPolicy]: both add and revoke
 * require [MedcoreAuthority.PATIENT_UPDATE]. A future clinical-
 * role differentiation slice may split them (e.g., nurses can
 * add insurance IDs but not revoke them), at which point new
 * authorities land with their own ADR.
 *
 * V17 defense in depth — the DB's `p_patient_identifier_update`
 * policy ALSO requires OWNER/ADMIN, so a bug in this policy
 * does not open the door at the persistence layer.
 */
@Component
class RevokePatientIdentifierPolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<RevokePatientIdentifierCommand> {

    override fun check(command: RevokePatientIdentifierCommand, context: WriteContext) {
        val resolution = authorityResolver.resolveFor(
            userId = context.principal.userId,
            tenantSlug = command.slug,
        )
        val authorities = when (resolution) {
            is AuthorityResolution.Denied ->
                throw WriteAuthorizationException(resolution.reason)
            is AuthorityResolution.Granted -> resolution.authorities
        }
        if (MedcoreAuthority.PATIENT_UPDATE !in authorities) {
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }
    }
}
