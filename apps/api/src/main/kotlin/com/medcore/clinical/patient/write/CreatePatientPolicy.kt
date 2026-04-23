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
 * Authorization policy for [CreatePatientCommand] (Phase 4A.2).
 *
 * Single check: caller must hold
 * [MedcoreAuthority.PATIENT_CREATE] in the target tenant. Per
 * the Phase 4A.1 role map, that authority is granted to OWNER
 * and ADMIN only — MEMBER cannot create patients. Denials emit
 * `AUTHZ_WRITE_DENIED` via [CreatePatientAuditor.onDenied] and
 * return 403 `auth.forbidden`.
 *
 * **No handler-side authz guard** is needed on this command:
 * there is no existing target row to re-evaluate authority
 * against (it's a create). Compare to 3J.N's `UpdateTenantMembershipRole`
 * where the target-OWNER guard runs inside the handler because
 * the target's role is only known after the RLS-GUCd read. For
 * a pure create, the policy layer alone is sufficient.
 */
@Component
class CreatePatientPolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<CreatePatientCommand> {

    override fun check(command: CreatePatientCommand, context: WriteContext) {
        val resolution = authorityResolver.resolveFor(
            userId = context.principal.userId,
            tenantSlug = command.slug,
        )
        val authorities = when (resolution) {
            is AuthorityResolution.Denied ->
                throw WriteAuthorizationException(resolution.reason)
            is AuthorityResolution.Granted -> resolution.authorities
        }
        if (MedcoreAuthority.PATIENT_CREATE !in authorities) {
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }
    }
}
