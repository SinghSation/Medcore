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
}
