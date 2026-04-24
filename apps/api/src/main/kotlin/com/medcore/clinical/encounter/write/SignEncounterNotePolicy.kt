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
 * Authorization policy for [SignEncounterNoteCommand]
 * (Phase 4D.5).
 *
 * Requires [MedcoreAuthority.NOTE_SIGN]. Per the 4D.5 role map
 * (`MembershipRoleAuthorities`):
 *   - OWNER + ADMIN hold `NOTE_SIGN`
 *   - MEMBER does NOT
 *
 * NOTE_SIGN is deliberately separate from NOTE_WRITE. Today the
 * two travel together (any role with one has the other) but
 * future clinical role differentiation (nurse writes, physician
 * signs) will split them — introducing `NOTE_SIGN` now prevents
 * a breaking rename later (Rule 07 forward-only).
 */
@Component
class SignEncounterNotePolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<SignEncounterNoteCommand> {

    override fun check(
        command: SignEncounterNoteCommand,
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
        if (MedcoreAuthority.NOTE_SIGN !in authorities) {
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }
    }
}
