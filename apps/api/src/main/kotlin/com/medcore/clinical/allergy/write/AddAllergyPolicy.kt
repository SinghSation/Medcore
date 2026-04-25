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
 * Authorization policy for [AddAllergyCommand] (Phase 4E.1).
 *
 * Requires [MedcoreAuthority.ALLERGY_WRITE]. Per the 4E.1 role
 * map (`MembershipRoleAuthorities`):
 *   - OWNER + ADMIN hold `ALLERGY_WRITE`
 *   - MEMBER does NOT — MEMBER callers get 403 on POST
 *
 * RLS is belt-and-braces: V24's `p_allergy_insert` policy also
 * checks `role IN ('OWNER','ADMIN')` at the DB layer. Kotlin
 * policy is the primary user-facing gate.
 */
@Component
class AddAllergyPolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<AddAllergyCommand> {

    override fun check(
        command: AddAllergyCommand,
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
