package com.medcore.clinical.allergy.write

import com.medcore.clinical.allergy.read.ListPatientAllergiesAuditor
import com.medcore.clinical.allergy.read.ListPatientAllergiesCommand
import com.medcore.clinical.allergy.read.ListPatientAllergiesPolicy
import com.medcore.clinical.allergy.read.ListPatientAllergiesResult
import com.medcore.platform.read.ReadGate
import com.medcore.platform.write.PhiRlsTxHook
import com.medcore.platform.write.WriteGate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * Spring wiring for the clinical-allergy gates (Phase 4E.1).
 *
 * Mirrors `EncounterWriteConfig` topology: one `@Bean` per
 * command type, explicit wiring, `PhiRlsTxHook` for both RLS
 * GUCs, no AOP / auto-magic.
 *
 * Three gates:
 *
 *   - [addAllergyGate]            — POST   .../allergies
 *   - [updateAllergyGate]         — PATCH  .../allergies/{id}
 *   - [listPatientAllergiesGate]  — GET    .../allergies
 *
 * No Get-by-ID surface in 4E.1 — the banner / management view
 * gets the full list. A single-allergy GET endpoint can land
 * additively when a future workflow needs it.
 */
@Configuration(proxyBeanMethods = false)
class AllergyWriteConfig {

    @Bean
    fun addAllergyGate(
        policy: AddAllergyPolicy,
        auditor: AddAllergyAuditor,
        validator: AddAllergyValidator,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): WriteGate<AddAllergyCommand, AllergySnapshot> =
        WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            validator = validator,
            txHook = phiRlsTxHook,
        )

    @Bean
    fun updateAllergyGate(
        policy: UpdateAllergyPolicy,
        auditor: UpdateAllergyAuditor,
        validator: UpdateAllergyValidator,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): WriteGate<UpdateAllergyCommand, UpdateAllergyOutcome> =
        WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            validator = validator,
            txHook = phiRlsTxHook,
        )

    @Bean
    fun listPatientAllergiesGate(
        policy: ListPatientAllergiesPolicy,
        auditor: ListPatientAllergiesAuditor,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): ReadGate<ListPatientAllergiesCommand, ListPatientAllergiesResult> =
        ReadGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            txHook = phiRlsTxHook,
        )
}
