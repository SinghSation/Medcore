package com.medcore.clinical.allergy.read

import com.medcore.platform.read.ReadAuthzPolicy
import com.medcore.platform.security.AuthorityResolution
import com.medcore.platform.security.AuthorityResolver
import com.medcore.platform.security.MedcoreAuthority
import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import org.springframework.stereotype.Component

/**
 * Authorization policy for [ListPatientAllergiesCommand]
 * (Phase 4E.1).
 *
 * Requires [MedcoreAuthority.ALLERGY_READ]. All three roles
 * (OWNER / ADMIN / MEMBER) hold it per the 4E.1 role map —
 * the allergy banner is a clinical-safety surface that every
 * member who reads a chart must see.
 */
@Component
class ListPatientAllergiesPolicy(
    private val authorityResolver: AuthorityResolver,
) : ReadAuthzPolicy<ListPatientAllergiesCommand> {

    override fun check(
        command: ListPatientAllergiesCommand,
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
        if (MedcoreAuthority.ALLERGY_READ !in authorities) {
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }
    }
}
