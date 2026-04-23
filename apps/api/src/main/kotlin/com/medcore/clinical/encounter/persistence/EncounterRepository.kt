package com.medcore.clinical.encounter.persistence

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Repository for [EncounterEntity] (Phase 4C.1, VS1 Chunk D).
 *
 * Interface only — the minimum surface needed by Chunk D's
 * read + write stacks:
 *   - [findById] (inherited) — used by `GetEncounterHandler`.
 *   - [saveAndFlush] (inherited) — used by `StartEncounterHandler`
 *     to surface the generated row_version immediately.
 *
 * No derived-query methods in 4C.1. Future Phase 4C slices that
 * need per-patient timeline queries will add them alongside their
 * handler, not speculatively here.
 *
 * ArchUnit Rule 1 access perimeter matches the patient repository
 * pattern: accessible from `..write..`, `..read..`, `..service..`,
 * `..persistence..`, `..platform..`.
 */
interface EncounterRepository : JpaRepository<EncounterEntity, UUID>
