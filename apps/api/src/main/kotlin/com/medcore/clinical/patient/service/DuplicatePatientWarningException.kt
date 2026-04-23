package com.medcore.clinical.patient.service

/**
 * Thrown by [com.medcore.clinical.patient.write.CreatePatientHandler]
 * when [DuplicatePatientDetector] surfaces candidate matches and
 * the caller did NOT opt out of the warning via the
 * `X-Confirm-Duplicate: true` header (Phase 4A.2).
 *
 * ### Wire mapping
 *
 * Phase 3G's `GlobalExceptionHandler.onDuplicatePatientWarning`
 * (added in 4A.2) maps to 409 with the dedicated error code
 * `clinical.patient.duplicate_warning` and a `details.candidates`
 * array of `{patientId, mrn}` pairs. Distinct from the generic
 * `resource.conflict` 409 (WriteConflictException) because the
 * retry contract is different — clients retry this case by
 * adding a header, not by re-fetching state.
 *
 * ### Why a dedicated exception type (not WriteConflictException)
 *
 * [com.medcore.platform.write.WriteConflictException] emits
 * `details.reason = <code>` — a single closed-enum slug. This
 * exception needs to emit `details.candidates = [...]` — an
 * array payload. Stretching WriteConflictException to carry a
 * candidate list would blur the "one slug, one concept" contract
 * and make the 409 envelope less scannable. A dedicated exception
 * + handler keeps each code's `details` shape crisp.
 *
 * ### Throw-safety
 *
 * Thrown INSIDE the gate's transaction (before MRN is minted,
 * before any save). The tx rolls back — no partial state
 * persists, the counter is not advanced (the detector runs
 * BEFORE `MrnGenerator.generate`). Audit emission is NOT
 * triggered — a warning isn't an authorization denial, and
 * treating every retry with and without the header as an
 * audit pair would flood the chain.
 *
 * ### Leakage discipline
 *
 * [candidates] carries only `patientId` (UUID) + `mrn` (tenant-
 * scoped opaque identifier). No name, no DOB, no demographics.
 * See [DuplicatePatientDetector] for the design rationale.
 */
class DuplicatePatientWarningException(
    val candidates: List<DuplicateCandidate>,
) : RuntimeException(
    "duplicate patient warning: ${candidates.size} candidate(s)",
)
