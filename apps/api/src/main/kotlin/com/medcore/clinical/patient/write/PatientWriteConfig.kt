package com.medcore.clinical.patient.write

import com.medcore.clinical.patient.read.GetPatientAuditor
import com.medcore.clinical.patient.read.GetPatientCommand
import com.medcore.clinical.patient.read.GetPatientPolicy
import com.medcore.platform.read.ReadGate
import com.medcore.platform.write.PhiRlsTxHook
import com.medcore.platform.write.WriteGate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * Spring wiring for clinical patient gates — writes + reads
 * (Phase 4A.2 original, Phase 4A.4 extended with read gates).
 *
 * Every patient gate (read or write) uses [PhiRlsTxHook] as
 * its tx-local state hook so both RLS GUCs are set in-tx
 * before the handler's queries run. The V14+ RLS policies
 * key on BOTH `app.current_tenant_id` AND `app.current_user_id`;
 * using any other hook (e.g., `TenancyRlsTxHook`) produces
 * silent zero-row reads on PHI tables.
 *
 * Pattern: one `@Bean` per command type, explicit wiring,
 * `proxyBeanMethods = false`. Read gates go through
 * [ReadGate]; write gates through [WriteGate]. Same
 * construction discipline, same hook.
 */
@Configuration(proxyBeanMethods = false)
class PatientWriteConfig {

    @Bean
    fun createPatientGate(
        policy: CreatePatientPolicy,
        auditor: CreatePatientAuditor,
        validator: CreatePatientValidator,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): WriteGate<CreatePatientCommand, PatientSnapshot> =
        WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            validator = validator,
            txHook = phiRlsTxHook,
        )

    @Bean
    fun updatePatientDemographicsGate(
        policy: UpdatePatientDemographicsPolicy,
        auditor: UpdatePatientDemographicsAuditor,
        validator: UpdatePatientDemographicsValidator,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): WriteGate<UpdatePatientDemographicsCommand, UpdatePatientDemographicsSnapshot> =
        WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            validator = validator,
            txHook = phiRlsTxHook,
        )

    // --- Phase 4A.3: patient identifier satellite writes ---

    @Bean
    fun addPatientIdentifierGate(
        policy: AddPatientIdentifierPolicy,
        auditor: AddPatientIdentifierAuditor,
        validator: AddPatientIdentifierValidator,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): WriteGate<AddPatientIdentifierCommand, PatientIdentifierSnapshot> =
        WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            validator = validator,
            txHook = phiRlsTxHook,
        )

    @Bean
    fun revokePatientIdentifierGate(
        policy: RevokePatientIdentifierPolicy,
        auditor: RevokePatientIdentifierAuditor,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): WriteGate<RevokePatientIdentifierCommand, PatientIdentifierSnapshot> =
        // No validator — DELETE has no body, and the path variable's
        // UUID format is enforced by @PathVariable binding. Precedent
        // from 3J.N's revokeTenantMembershipGate.
        WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            validator = null,
            txHook = phiRlsTxHook,
        )

    // --- Phase 4A.4: patient read ---

    /**
     * First [ReadGate] bean in Medcore (Phase 4A.4). Wires
     * `PhiRlsTxHook` (reused from writes) so both RLS GUCs are
     * set before the handler's SELECT runs. Audit emission on
     * success runs inside the read-only transaction (ADR-003
     * §2 extended to reads).
     */
    @Bean
    fun getPatientGate(
        policy: GetPatientPolicy,
        auditor: GetPatientAuditor,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): ReadGate<GetPatientCommand, PatientSnapshot> =
        ReadGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            txHook = phiRlsTxHook,
        )
}
