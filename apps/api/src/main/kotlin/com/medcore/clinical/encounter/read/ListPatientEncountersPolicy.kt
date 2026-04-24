package com.medcore.clinical.encounter.read

import com.medcore.platform.read.ReadAuthzPolicy
import com.medcore.platform.security.AuthorityResolution
import com.medcore.platform.security.AuthorityResolver
import com.medcore.platform.security.MedcoreAuthority
import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import org.springframework.stereotype.Component

/**
 * Authorization policy for [ListPatientEncountersCommand]
 * (Phase 4C.3).
 *
 * Requires [MedcoreAuthority.ENCOUNTER_READ]. All three roles
 * (OWNER / ADMIN / MEMBER) hold it per the 4C.1 role map — any
 * ACTIVE member of the tenant can list a patient's encounters.
 * Matches the posture of [GetEncounterPolicy] and
 * [ListEncounterNotesPolicy] — reads are broadly available,
 * writes are stratified.
 */
@Component
class ListPatientEncountersPolicy(
    private val authorityResolver: AuthorityResolver,
) : ReadAuthzPolicy<ListPatientEncountersCommand> {

    override fun check(
        command: ListPatientEncountersCommand,
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
        if (MedcoreAuthority.ENCOUNTER_READ !in authorities) {
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }
    }
}
