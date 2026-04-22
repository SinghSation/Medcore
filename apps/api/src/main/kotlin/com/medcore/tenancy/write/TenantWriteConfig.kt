package com.medcore.tenancy.write

import com.medcore.platform.write.TenancyRlsTxHook
import com.medcore.platform.write.WriteGate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * Spring wiring for tenancy write-gates (Phase 3J.2).
 *
 * Each command type gets its own `WriteGate<CMD, R>` bean. Keeping
 * the gates small and single-purpose means new tenancy writes (3J.3+,
 * e.g., membership invite/remove) add one bean method here without
 * restructuring the generic gate or fighting type inference in
 * callers.
 *
 * `proxyBeanMethods = false` — these factories produce no beans
 * that other factories need to call via the proxy; the lite-mode
 * speeds up context refresh in tests.
 */
@Configuration(proxyBeanMethods = false)
class TenantWriteConfig {

    @Bean
    fun updateTenantDisplayNameGate(
        policy: UpdateTenantDisplayNamePolicy,
        auditor: UpdateTenantDisplayNameAuditor,
        validator: UpdateTenantDisplayNameValidator,
        txManager: PlatformTransactionManager,
        tenancyRlsTxHook: TenancyRlsTxHook,
    ): WriteGate<UpdateTenantDisplayNameCommand, TenantSnapshot> =
        WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            validator = validator,
            txHook = tenancyRlsTxHook,
        )

    @Bean
    fun inviteTenantMembershipGate(
        policy: InviteTenantMembershipPolicy,
        auditor: InviteTenantMembershipAuditor,
        validator: InviteTenantMembershipValidator,
        txManager: PlatformTransactionManager,
        tenancyRlsTxHook: TenancyRlsTxHook,
    ): WriteGate<InviteTenantMembershipCommand, MembershipSnapshot> =
        WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            validator = validator,
            txHook = tenancyRlsTxHook,
        )

    @Bean
    fun updateTenantMembershipRoleGate(
        policy: UpdateTenantMembershipRolePolicy,
        auditor: UpdateTenantMembershipRoleAuditor,
        validator: UpdateTenantMembershipRoleValidator,
        txManager: PlatformTransactionManager,
        tenancyRlsTxHook: TenancyRlsTxHook,
    ): WriteGate<UpdateTenantMembershipRoleCommand, RoleUpdateSnapshot> =
        WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            validator = validator,
            txHook = tenancyRlsTxHook,
        )

    @Bean
    fun revokeTenantMembershipGate(
        policy: RevokeTenantMembershipPolicy,
        auditor: RevokeTenantMembershipAuditor,
        txManager: PlatformTransactionManager,
        tenancyRlsTxHook: TenancyRlsTxHook,
    ): WriteGate<RevokeTenantMembershipCommand, RevokeSnapshot> =
        // No validator — revoke has no body, and the path variable's
        // UUID format is enforced by @PathVariable binding.
        WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            validator = null,
            txHook = tenancyRlsTxHook,
        )
}
