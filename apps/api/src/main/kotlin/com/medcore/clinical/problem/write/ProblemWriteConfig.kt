package com.medcore.clinical.problem.write

import com.medcore.clinical.problem.read.ListPatientProblemsAuditor
import com.medcore.clinical.problem.read.ListPatientProblemsCommand
import com.medcore.clinical.problem.read.ListPatientProblemsPolicy
import com.medcore.clinical.problem.read.ListPatientProblemsResult
import com.medcore.platform.read.ReadGate
import com.medcore.platform.write.PhiRlsTxHook
import com.medcore.platform.write.WriteGate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * Spring wiring for the clinical-problem gates (Phase 4E.2).
 *
 * Mirrors `AllergyWriteConfig` topology: one `@Bean` per
 * command type, explicit wiring, `PhiRlsTxHook` for both RLS
 * GUCs, no AOP / auto-magic.
 *
 * Three gates:
 *
 *   - [addProblemGate]            — POST   .../problems
 *   - [updateProblemGate]         — PATCH  .../problems/{id}
 *   - [listPatientProblemsGate]   — GET    .../problems
 *
 * No Get-by-ID surface in 4E.2 — the chart shows the full
 * list. A single-problem GET endpoint can land additively
 * when a future workflow needs it (e.g., a deep-link to a
 * problem detail page).
 */
@Configuration(proxyBeanMethods = false)
class ProblemWriteConfig {

    @Bean
    fun addProblemGate(
        policy: AddProblemPolicy,
        auditor: AddProblemAuditor,
        validator: AddProblemValidator,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): WriteGate<AddProblemCommand, ProblemSnapshot> =
        WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            validator = validator,
            txHook = phiRlsTxHook,
        )

    @Bean
    fun updateProblemGate(
        policy: UpdateProblemPolicy,
        auditor: UpdateProblemAuditor,
        validator: UpdateProblemValidator,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): WriteGate<UpdateProblemCommand, UpdateProblemOutcome> =
        WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            validator = validator,
            txHook = phiRlsTxHook,
        )

    @Bean
    fun listPatientProblemsGate(
        policy: ListPatientProblemsPolicy,
        auditor: ListPatientProblemsAuditor,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): ReadGate<ListPatientProblemsCommand, ListPatientProblemsResult> =
        ReadGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            txHook = phiRlsTxHook,
        )
}
