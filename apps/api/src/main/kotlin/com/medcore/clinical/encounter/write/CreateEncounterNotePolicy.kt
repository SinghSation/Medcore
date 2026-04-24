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
 * Authorization policy for [CreateEncounterNoteCommand]
 * (Phase 4D.1, VS1 Chunk E).
 *
 * Requires [MedcoreAuthority.NOTE_WRITE]. Per the 4D.1 role map
 * (`MembershipRoleAuthorities`):
 *   - OWNER + ADMIN hold `NOTE_WRITE`
 *   - MEMBER does NOT — MEMBER callers get 403 on POST
 *
 * RLS is belt-and-braces: V19's `p_encounter_note_insert`
 * policy also checks `role IN ('OWNER','ADMIN')` at the DB
 * layer. Kotlin policy is the primary user-facing gate.
 */
@Component
class CreateEncounterNotePolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<CreateEncounterNoteCommand> {

    override fun check(
        command: CreateEncounterNoteCommand,
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
        if (MedcoreAuthority.NOTE_WRITE !in authorities) {
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }
    }
}
