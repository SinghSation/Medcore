package com.medcore.clinical.patient.write

import com.medcore.platform.write.PhiRlsTxHook
import com.medcore.platform.write.WriteGate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * Spring wiring for clinical patient write-gates (Phase 4A.2).
 *
 * First consumer of [PhiRlsTxHook] (Phase 4A.0). Every patient
 * write gate uses the PHI-scope hook, NOT the tenancy-scope
 * `TenancyRlsTxHook` — the V14 RLS policies key on BOTH
 * `app.current_tenant_id` AND `app.current_user_id`, and only
 * [PhiRlsTxHook] sets both inside the gate's transaction.
 *
 * Pattern mirrors `TenantWriteConfig` — one @Bean per command
 * type, explicit wiring, `proxyBeanMethods = false`.
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
}
