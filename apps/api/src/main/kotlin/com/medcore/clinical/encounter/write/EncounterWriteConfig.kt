package com.medcore.clinical.encounter.write

import com.medcore.clinical.encounter.read.GetEncounterAuditor
import com.medcore.clinical.encounter.read.GetEncounterCommand
import com.medcore.clinical.encounter.read.GetEncounterPolicy
import com.medcore.platform.read.ReadGate
import com.medcore.platform.write.PhiRlsTxHook
import com.medcore.platform.write.WriteGate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * Spring wiring for clinical-encounter gates — writes + reads
 * (Phase 4C.1, VS1 Chunk D).
 *
 * Mirrors `PatientWriteConfig`'s topology: one `@Bean` per
 * command type, explicit wiring, `PhiRlsTxHook` for both RLS
 * GUCs, no AOP / auto-magic.
 */
@Configuration(proxyBeanMethods = false)
class EncounterWriteConfig {

    @Bean
    fun startEncounterGate(
        policy: StartEncounterPolicy,
        auditor: StartEncounterAuditor,
        validator: StartEncounterValidator,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): WriteGate<StartEncounterCommand, EncounterSnapshot> =
        WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            validator = validator,
            txHook = phiRlsTxHook,
        )

    @Bean
    fun getEncounterGate(
        policy: GetEncounterPolicy,
        auditor: GetEncounterAuditor,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): ReadGate<GetEncounterCommand, EncounterSnapshot> =
        ReadGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            txHook = phiRlsTxHook,
        )
}
