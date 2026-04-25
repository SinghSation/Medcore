package com.medcore.clinical.allergy.write

import com.medcore.platform.security.AuthorityResolution
import com.medcore.platform.security.AuthorityResolver
import com.medcore.platform.security.MedcoreAuthority
import com.medcore.platform.write.AuthzPolicy
import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import org.springframework.stereotype.Component

/**
 * Authorization policy for [UpdateAllergyCommand] (Phase 4E.1).
 *
 * Same gate as [AddAllergyPolicy]: requires
 * [MedcoreAuthority.ALLERGY_WRITE]. OWNER + ADMIN hold it;
 * MEMBER does not. The PATCH endpoint covers all status
 * transitions including the terminal ENTERED_IN_ERROR
 * retraction — there is deliberately no separate "revoke
 * authority" in 4E.1 (locked plan). If a future clinical-role
 * policy demands stricter retraction (e.g., "only attendings
 * may mark ENTERED_IN_ERROR"), an additional authority
 * lands then.
 */
@Component
class UpdateAllergyPolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<UpdateAllergyCommand> {

    override fun check(
        command: UpdateAllergyCommand,
        context: WriteContext,
    ) {
        val resolution = authorityResolver.resolveFor(
            userId = context.principal.userId,
            tenantSlug = command.slug,
        )
        val authorities = when (resolution) {
            is AuthorityResolution.Denied ->
                throw WriteAuthorizationException(resolution.reason)
            is AuthorityResolution.Granted -> resolution.authorities
        }
        if (MedcoreAuthority.ALLERGY_WRITE !in authorities) {
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }
    }
}
