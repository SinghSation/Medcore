package com.medcore.tenancy.write

import com.medcore.platform.security.AuthorityResolution
import com.medcore.platform.security.AuthorityResolver
import com.medcore.platform.security.MedcoreAuthority
import com.medcore.platform.write.AuthzPolicy
import com.medcore.platform.write.WriteAuthorizationException
import com.medcore.platform.write.WriteContext
import org.springframework.stereotype.Component

/**
 * Authorization policy for [RevokeTenantMembershipCommand]
 * (Phase 3J.N).
 *
 * Single check here:
 *
 * 1. **Base authority.** Caller must hold
 *    [MedcoreAuthority.MEMBERSHIP_REMOVE] (OWNER + ADMIN).
 *
 * The **target-OWNER guard** (ADMIN cannot revoke an OWNER)
 * lives in the handler — it requires reading the target row,
 * which is visible only after `TenancyRlsTxHook` has set
 * `app.current_user_id` inside the gate's transaction.
 * Handler-thrown `WriteAuthorizationException` is caught by
 * `WriteGate` and emits `onDenied` identically to a policy-
 * thrown denial.
 */
@Component
class RevokeTenantMembershipPolicy(
    private val authorityResolver: AuthorityResolver,
) : AuthzPolicy<RevokeTenantMembershipCommand> {

    override fun check(command: RevokeTenantMembershipCommand, context: WriteContext) {
        val resolution = authorityResolver.resolveFor(context.principal.userId, command.slug)
        val authorities = when (resolution) {
            is AuthorityResolution.Denied ->
                throw WriteAuthorizationException(resolution.reason)
            is AuthorityResolution.Granted -> resolution.authorities
        }

        if (MedcoreAuthority.MEMBERSHIP_REMOVE !in authorities) {
            throw WriteAuthorizationException(
                com.medcore.platform.write.WriteDenialReason.INSUFFICIENT_AUTHORITY,
            )
        }
    }
}
