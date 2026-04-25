package com.medcore.clinical.problem.read

import com.medcore.platform.read.ReadAuthzPolicy
import com.medcore.platform.security.AuthorityResolution
import com.medcore.platform.security.AuthorityResolver
import com.medcore.platform.security.MedcoreAuthority
import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import org.springframework.stereotype.Component

/**
 * Authorization policy for [ListPatientProblemsCommand]
 * (Phase 4E.2).
 *
 * Requires [MedcoreAuthority.PROBLEM_READ]. All three roles
 * (OWNER / ADMIN / MEMBER) hold it per the 4E.2 role map —
 * the problem list is chart context that every chart viewer
 * must see.
 */
@Component
class ListPatientProblemsPolicy(
    private val authorityResolver: AuthorityResolver,
) : ReadAuthzPolicy<ListPatientProblemsCommand> {

    override fun check(
        command: ListPatientProblemsCommand,
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
        if (MedcoreAuthority.PROBLEM_READ !in authorities) {
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }
    }
}
