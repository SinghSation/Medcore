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
 * Authorization policy for [ListEncounterNotesCommand]
 * (Phase 4D.1, VS1 Chunk E).
 *
 * Requires [MedcoreAuthority.NOTE_READ]. All three roles
 * (OWNER / ADMIN / MEMBER) hold it per the 4D.1 role map —
 * any ACTIVE member of the tenant can read notes.
 *
 * Retained for pattern discipline (clinical-write-pattern §12)
 * and future-proofing. Clinical role differentiation may split
 * this into `NOTE_READ_FULL` / `NOTE_READ_SUMMARY` later.
 */
@Component
class ListEncounterNotesPolicy(
    private val authorityResolver: AuthorityResolver,
) : ReadAuthzPolicy<ListEncounterNotesCommand> {

    override fun check(
        command: ListEncounterNotesCommand,
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
        if (MedcoreAuthority.NOTE_READ !in authorities) {
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }
    }
}
