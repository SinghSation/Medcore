package com.medcore.clinical.problem.write

import com.medcore.platform.security.AuthorityResolution
import com.medcore.platform.security.AuthorityResolver
import com.medcore.platform.security.MedcoreAuthority
import com.medcore.platform.write.AuthzPolicy
import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteDenialReason
import org.springframework.stereotype.Component

/**
 * Authorization policy for [UpdateProblemCommand] (Phase 4E.2).
 *
 * Same gate as [AddProblemPolicy]: requires
 * [MedcoreAuthority.PROBLEM_WRITE]. OWNER + ADMIN hold it;
 * MEMBER does not. The PATCH endpoint covers all status
 * transitions including the terminal ENTERED_IN_ERROR
 * retraction and the RESOLVED transition — there is
 * deliberately no separate "resolve authority" or "revoke
 * authority" in 4E.2 (locked plan). If a future clinical-role
 * policy demands stricter resolution / retraction (e.g.,
 * "only attendings may mark a problem RESOLVED"), an
 * additional authority lands then.
 */
@Component
class UpdateProblemPolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<UpdateProblemCommand> {

    override fun check(
        command: UpdateProblemCommand,
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
        if (MedcoreAuthority.PROBLEM_WRITE !in authorities) {
            throw WriteAuthorizationException(WriteDenialReason.INSUFFICIENT_AUTHORITY)
        }
    }
}
