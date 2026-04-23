package com.medcore.clinical.patient.read

import java.util.UUID

/**
 * Command for `GET /api/v1/tenants/{slug}/patients/{patientId}`
 * (Phase 4A.4).
 *
 * First read command in Medcore — follows the
 * `clinical-write-pattern.md` v1.2 §12 "Read path" addendum.
 *
 * ### Shape
 *
 * Immutable data class carrying only path variables — reads
 * have no body. `slug` is the tenant slug (caller-facing);
 * the handler resolves to tenant UUID via
 * `TenantRepository.findBySlug`.
 *
 * ### PHI boundary
 *
 * The command itself carries no PHI — just identifiers. The
 * handler's result ([com.medcore.clinical.patient.write.PatientSnapshot]
 * reused from 4A.2) DOES carry PHI; the auditor records the
 * access but NEVER echoes the disclosed fields into the audit
 * reason slug.
 */
data class GetPatientCommand(
    val slug: String,
    val patientId: UUID,
)
