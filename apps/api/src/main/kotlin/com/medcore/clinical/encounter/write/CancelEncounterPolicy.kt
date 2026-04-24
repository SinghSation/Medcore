package com.medcore.clinical.encounter.write

import com.medcore.platform.security.AuthorityResolution
import com.medcore.platform.security.AuthorityResolver
import com.medcore.platform.security.MedcoreAuthority
import com.medcore.platform.write.AuthzPolicy
import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import org.springframework.stereotype.Component

/**
 * Authorization policy for [CancelEncounterCommand] (Phase 4C.5).
 *
 * Requires [MedcoreAuthority.ENCOUNTER_WRITE] — same surface as
 * [FinishEncounterPolicy]. MEMBER receives 403.
 */
@Component
class CancelEncounterPolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<CancelEncounterCommand> {

    override fun check(
        command: CancelEncounterCommand,
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
        if (MedcoreAuthority.ENCOUNTER_WRITE !in authorities) {
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }
    }
}
