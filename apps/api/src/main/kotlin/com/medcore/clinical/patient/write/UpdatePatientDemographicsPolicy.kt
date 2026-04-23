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
 * Authorization policy for [UpdatePatientDemographicsCommand]
 * (Phase 4A.2).
 *
 * Single check: caller must hold
 * [MedcoreAuthority.PATIENT_UPDATE] in the target tenant.
 * Matches the Phase 4A.1 role map — OWNER and ADMIN only.
 * MEMBER holds `PATIENT_READ` only and is refused.
 *
 * No handler-side guard is required for update: the patient row
 * being edited is bounded by the same tenant scope the policy
 * already checked; no row-level dynamic authz lives in the 4A.2
 * design. (A future clinical-role differentiation slice may
 * introduce row-level checks — e.g., CLINICIAN can only edit
 * patients on their assigned service line — but that is
 * explicitly out of 4A.2 scope.)
 */
@Component
class UpdatePatientDemographicsPolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<UpdatePatientDemographicsCommand> {

    override fun check(command: UpdatePatientDemographicsCommand, context: WriteContext) {
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
