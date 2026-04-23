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
 * Authorization policy for [GetEncounterCommand] (Phase 4C.1,
 * VS1 Chunk D).
 *
 * Requires [MedcoreAuthority.ENCOUNTER_READ]. Per the 4C.1 role
 * map (MembershipRoleAuthorities), all three roles (OWNER /
 * ADMIN / MEMBER) hold `ENCOUNTER_READ` — pass-through for any
 * ACTIVE member of the tenant.
 *
 * Retained for pattern discipline (clinical-write-pattern §12)
 * and future-proofing (clinical-role differentiation slice can
 * split ENCOUNTER_READ into ENCOUNTER_READ_FULL / ENCOUNTER_READ_SUMMARY
 * here).
 */
@Component
class GetEncounterPolicy(
    private val authorityResolver: AuthorityResolver,
) : ReadAuthzPolicy<GetEncounterCommand> {

    override fun check(command: GetEncounterCommand, context: WriteContext) {
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
