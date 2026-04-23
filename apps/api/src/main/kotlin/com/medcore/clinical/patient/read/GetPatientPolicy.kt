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
 * Authorization policy for [GetPatientCommand] (Phase 4A.4).
 *
 * Requires the caller to hold
 * [MedcoreAuthority.PATIENT_READ]. Per the Phase 4A.1 role
 * map, all three roles (OWNER, ADMIN, MEMBER) hold
 * `PATIENT_READ` — so in 4A.4 this check is effectively
 * pass-through for any ACTIVE member of the tenant.
 *
 * ### Why keep the policy anyway
 *
 * 1. **Pattern discipline** — the clinical-write-pattern v1.2
 *    §12 requires every command to have a policy. Skipping
 *    this because "it's always allowed today" is exactly
 *    the drift the pattern prevents.
 * 2. **Future-proofing** — clinical-role differentiation
 *    (CLINICIAN / NURSE / STAFF) will likely split
 *    `PATIENT_READ` into `PATIENT_READ_FULL` /
 *    `PATIENT_READ_SUMMARY` at some point. The policy is
 *    the designed place for that differentiation to land.
 * 3. **Symmetry** — writes gate on authority; reads should
 *    too. No asymmetric bypass paths.
 *
 * ### Denial paths
 *
 * In practice, policy-level denials are rare here because
 * the `TenantContextFilter` refuses non-members and
 * SUSPENDED members before this policy runs. The policy
 * catches:
 *
 * - Future consumers that hold ACTIVE membership but lack
 *   `PATIENT_READ` (if the role map ever revokes it from a
 *   specific role — currently none do).
 */
@Component
class GetPatientPolicy(
    private val authorityResolver: AuthorityResolver,
) : ReadAuthzPolicy<GetPatientCommand> {

    override fun check(command: GetPatientCommand, context: WriteContext) {
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
