package com.medcore.clinical.encounter.write

import com.medcore.clinical.encounter.read.GetEncounterAuditor
import com.medcore.clinical.encounter.read.GetEncounterCommand
import com.medcore.clinical.encounter.read.GetEncounterPolicy
import com.medcore.clinical.encounter.read.ListEncounterNotesAuditor
import com.medcore.clinical.encounter.read.ListEncounterNotesCommand
import com.medcore.clinical.encounter.read.ListEncounterNotesPolicy
import com.medcore.clinical.encounter.read.ListEncounterNotesResult
import com.medcore.clinical.encounter.read.ListPatientEncountersAuditor
import com.medcore.clinical.encounter.read.ListPatientEncountersCommand
import com.medcore.clinical.encounter.read.ListPatientEncountersPolicy
import com.medcore.clinical.encounter.read.ListPatientEncountersResult
import com.medcore.platform.read.ReadGate
import com.medcore.platform.write.PhiRlsTxHook
import com.medcore.platform.write.WriteGate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * Spring wiring for clinical-encounter gates — writes + reads.
 *
 * - 4C.1 (VS1 Chunk D): `startEncounter`, `getEncounter`.
 * - 4D.1 (VS1 Chunk E): `createEncounterNote`, `listEncounterNotes`.
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

    // --- Phase 4D.1: encounter notes (VS1 Chunk E) ---

    @Bean
    fun createEncounterNoteGate(
        policy: CreateEncounterNotePolicy,
        auditor: CreateEncounterNoteAuditor,
        validator: CreateEncounterNoteValidator,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): WriteGate<CreateEncounterNoteCommand, EncounterNoteSnapshot> =
        WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            validator = validator,
            txHook = phiRlsTxHook,
        )

    @Bean
    fun listEncounterNotesGate(
        policy: ListEncounterNotesPolicy,
        auditor: ListEncounterNotesAuditor,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): ReadGate<ListEncounterNotesCommand, ListEncounterNotesResult> =
        ReadGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            txHook = phiRlsTxHook,
        )

    // --- Phase 4C.3: per-patient encounter list ---

    @Bean
    fun listPatientEncountersGate(
        policy: ListPatientEncountersPolicy,
        auditor: ListPatientEncountersAuditor,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): ReadGate<ListPatientEncountersCommand, ListPatientEncountersResult> =
        ReadGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            txHook = phiRlsTxHook,
        )

    // --- Phase 4D.5: encounter note signing ---

    @Bean
    fun signEncounterNoteGate(
        policy: SignEncounterNotePolicy,
        auditor: SignEncounterNoteAuditor,
        txManager: PlatformTransactionManager,
        phiRlsTxHook: PhiRlsTxHook,
    ): WriteGate<SignEncounterNoteCommand, EncounterNoteSnapshot> =
        WriteGate(
            policy = policy,
            auditor = auditor,
            txManager = txManager,
            validator = null,
            txHook = phiRlsTxHook,
        )
}
