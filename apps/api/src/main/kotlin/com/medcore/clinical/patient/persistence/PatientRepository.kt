package com.medcore.clinical.patient.persistence

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Repository for [PatientEntity] (Phase 4A.1).
 *
 * Interface only — ships alongside the entity + migration but
 * has NO callers yet. Phase 4A.2 introduces the first consumer
 * (`PatientService` for reads + `*Handler` classes for writes
 * via WriteGate). ArchUnit Rule 1 enforces access perimeter:
 * this repository is accessible only from `..write..`,
 * `..service..`, `..persistence..`, `..platform..`, or
 * `..identity..` packages.
 *
 * The Rule 13 allowance on `ClinicalDisciplineArchTest` is
 * REMOVED when 4A.2 lands the first service; at that moment
 * every `@Transactional` method in `clinical..service` MUST
 * depend on `PhiSessionContext`.
 *
 * **No query methods in 4A.1.** 4A.2's handler layer will add
 * `findByTenantIdAndMrn`, duplicate-detection queries, etc.
 * Adding methods speculatively now would commit to query shapes
 * before the handler needs them.
 */
interface PatientRepository : JpaRepository<PatientEntity, UUID>
