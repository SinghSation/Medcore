package com.medcore.clinical.patient.persistence

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Repository for [PatientIdentifierEntity] (Phase 4A.1).
 *
 * Interface only — satellite to [PatientRepository]. No callers
 * in 4A.1; 4A.2's handler layer introduces consumers.
 */
interface PatientIdentifierRepository : JpaRepository<PatientIdentifierEntity, UUID>
