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
 * Authorization policy for [FinishEncounterCommand] (Phase 4C.5).
 *
 * Requires [MedcoreAuthority.ENCOUNTER_WRITE] (OWNER + ADMIN).
 * MEMBER does not hold it; MEMBER callers receive 403.
 *
 * Finish + cancel reuse `ENCOUNTER_WRITE` deliberately — no new
 * authority is introduced in 4C.5. Clinical role
 * differentiation (physician-only finish, nurse-only cancel)
 * is an explicit carry-forward.
 */
@Component
class FinishEncounterPolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<FinishEncounterCommand> {

    override fun check(
        command: FinishEncounterCommand,
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
