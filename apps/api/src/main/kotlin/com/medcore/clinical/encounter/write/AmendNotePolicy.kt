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
 * Authorization policy for [AmendNoteCommand] (Phase 4D.6).
 *
 * Requires [MedcoreAuthority.NOTE_WRITE] — the same authority
 * that gates create-note. Per the locked 4D.6 plan we deliberately
 * did NOT introduce a separate `NOTE_AMEND` authority: today
 * NOTE_WRITE callers (OWNER + ADMIN) are exactly the population
 * we want to permit. If a future clinical role split (e.g.
 * physicians-only-may-amend) emerges, a dedicated authority
 * lands in that slice without breaking this contract.
 *
 * Per the 4D.1 role map (`MembershipRoleAuthorities`):
 *   - OWNER + ADMIN hold `NOTE_WRITE`
 *   - MEMBER does NOT — MEMBER callers get 403 on POST
 *
 * Sign happens via the existing 4D.5 `NOTE_SIGN` policy —
 * orthogonal authority surface; signing an amendment uses the
 * same code path as signing a fresh draft.
 */
@Component
class AmendNotePolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<AmendNoteCommand> {

    override fun check(
        command: AmendNoteCommand,
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
