package com.medcore.clinical.patient.read

import com.medcore.platform.read.ReadAuthzPolicy
import com.medcore.platform.security.AuthorityResolution
import com.medcore.platform.security.AuthorityResolver
import com.medcore.platform.security.MedcoreAuthority
import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import org.springframework.stereotype.Component

/**
 * Authorization policy for [ListPatientsCommand] (Phase 4B.1).
 *
 * Same contract as [GetPatientPolicy]: requires
 * [MedcoreAuthority.PATIENT_READ]. Per the 4A.1 role map all
 * three roles (OWNER / ADMIN / MEMBER) hold `PATIENT_READ`, so
 * in practice this check pass-throughs for any ACTIVE member of
 * the tenant.
 *
 * The policy is retained for pattern discipline
 * (clinical-write-pattern.md §12 requires every command to
 * have a policy), future-proofing (split into
 * `PATIENT_READ_SUMMARY` vs `PATIENT_READ_FULL` at a later
 * role-differentiation slice), and symmetry with writes.
 */
@Component
class ListPatientsPolicy(
    private val authorityResolver: AuthorityResolver,
) : ReadAuthzPolicy<ListPatientsCommand> {

    override fun check(command: ListPatientsCommand, context: WriteContext) {
        val resolution = authorityResolver.resolveFor(
            userId = context.principal.userId,
            tenantSlug = command.slug,
        )
        val authorities = when (resolution) {
            is AuthorityResolution.Denied ->
                throw WriteAuthorizationException(resolution.reason)
            is AuthorityResolution.Granted -> resolution.authorities
        }
        if (MedcoreAuthority.PATIENT_READ !in authorities) {
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }
    }
}
