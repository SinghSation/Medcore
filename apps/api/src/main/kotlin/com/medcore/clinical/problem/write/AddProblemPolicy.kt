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
 * Authorization policy for [AddProblemCommand] (Phase 4E.2).
 *
 * Requires [MedcoreAuthority.PROBLEM_WRITE]. Per the 4E.2 role
 * map (`MembershipRoleAuthorities`):
 *   - OWNER + ADMIN hold `PROBLEM_WRITE`
 *   - MEMBER does NOT — MEMBER callers get 403 on POST
 *
 * RLS is belt-and-braces: V25's `p_problem_insert` policy also
 * checks `role IN ('OWNER','ADMIN')` at the DB layer. Kotlin
 * policy is the primary user-facing gate.
 */
@Component
class AddProblemPolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<AddProblemCommand> {

    override fun check(
        command: AddProblemCommand,
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
