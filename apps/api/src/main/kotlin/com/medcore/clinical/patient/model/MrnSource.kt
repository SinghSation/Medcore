package com.medcore.clinical.patient.model

/**
 * Provenance of a patient's MRN (Phase 4A.1).
 *
 * - [GENERATED] — MRN was minted by Medcore at patient creation
 *   via the Phase 4A.2 sequencing logic (tenant-scoped counter
 *   + tenant-configured prefix). The canonical case for pilot
 *   clinics starting fresh on Medcore.
 * - [IMPORTED] — MRN was supplied at import time from a
 *   pre-existing clinical system (data migration from another
 *   EHR, bulk import of an existing patient roster). The value
 *   preserves continuity with the source system; Medcore does
 *   NOT attempt to regenerate it.
 *
 * 4A.1 ships only the `GENERATED` path semantically (no import
 * slice yet). `IMPORTED` is reserved in the enum so the future
 * migration slice is strictly additive on the schema side.
 */
enum class MrnSource {
    GENERATED,
    IMPORTED,
}
