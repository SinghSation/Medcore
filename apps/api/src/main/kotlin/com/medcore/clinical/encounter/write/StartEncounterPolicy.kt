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
 * Authorization policy for [StartEncounterCommand] (Phase 4C.1,
 * VS1 Chunk D).
 *
 * Requires [MedcoreAuthority.ENCOUNTER_WRITE]. Per the 4C.1 role
 * map (MembershipRoleAuthorities):
 *   - OWNER + ADMIN hold `ENCOUNTER_WRITE`
 *   - MEMBER does NOT — MEMBER callers receive 403 on `POST`
 *
 * RLS provides defense-in-depth: V18's `p_encounter_insert`
 * policy also checks `role IN ('OWNER', 'ADMIN')` at the DB
 * layer. This Kotlin-side policy is the primary, user-facing
 * gate — RLS is the belt-and-braces backstop.
 */
@Component
class StartEncounterPolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<StartEncounterCommand> {

    override fun check(command: StartEncounterCommand, context: WriteContext) {
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
