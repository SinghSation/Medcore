package com.medcore.clinical.encounter.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.medcore.clinical.encounter.model.EncounterClass
import com.medcore.clinical.encounter.model.EncounterNoteStatus
import com.medcore.clinical.encounter.model.EncounterStatus
import com.medcore.clinical.encounter.read.ListEncounterNotesResult
import com.medcore.clinical.encounter.read.ListPatientEncountersResult
import com.medcore.clinical.encounter.write.CreateEncounterNoteCommand
import com.medcore.clinical.encounter.write.EncounterNoteSnapshot
import com.medcore.clinical.encounter.write.EncounterSnapshot
import com.medcore.clinical.encounter.write.StartEncounterCommand
import com.medcore.platform.write.WriteValidationException
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

// ============================================================================
// POST /api/v1/tenants/{slug}/patients/{patientId}/encounters — start request
// ============================================================================

/**
 * HTTP DTO for
 * `POST /api/v1/tenants/{slug}/patients/{patientId}/encounters`
 * (Phase 4C.1, VS1 Chunk D).
 *
 * `encounterClass` on the wire is the closed-enum name (`"AMB"`).
 * Unknown values throw [WriteValidationException] →
 * 422 `request.validation_failed|format`.
 */
data class StartEncounterRequest(
    @field:NotNull
    val encounterClass: String?,
) {
    fun toCommand(slug: String, patientId: UUID): StartEncounterCommand {
        val rawClass = encounterClass
            ?: throw WriteValidationException(
                field = "encounterClass",
                code = "required",
            )
        val parsedClass = try {
            enumValueOf<EncounterClass>(rawClass)
        } catch (ex: IllegalArgumentException) {
            throw WriteValidationException(
                field = "encounterClass",
                code = "format",
                cause = ex,
            )
        }
        return StartEncounterCommand(
            slug = slug,
            patientId = patientId,
            encounterClass = parsedClass,
        )
    }
}

// ============================================================================
// Response DTO
// ============================================================================

/**
 * Wire shape for encounter start + read responses
 * (Phase 4C.1, VS1 Chunk D).
 *
 * Mirrors [EncounterSnapshot] with enum NAMEs on the wire.
 * `@JsonInclude(NON_NULL)` omits nullable lifecycle timestamps
 * (`startedAt`, `finishedAt`) from the wire when unset — FHIR
 * cardinality semantics.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EncounterResponse(
    val id: UUID,
    val tenantId: UUID,
    val patientId: UUID,
    val status: EncounterStatus,
    val encounterClass: EncounterClass,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID,
    val updatedBy: UUID,
    val rowVersion: Long,
) {
    companion object {
        fun from(snapshot: EncounterSnapshot): EncounterResponse = EncounterResponse(
            id = snapshot.id,
            tenantId = snapshot.tenantId,
            patientId = snapshot.patientId,
            status = snapshot.status,
            encounterClass = snapshot.encounterClass,
            startedAt = snapshot.startedAt,
            finishedAt = snapshot.finishedAt,
            createdAt = snapshot.createdAt,
            updatedAt = snapshot.updatedAt,
            createdBy = snapshot.createdBy,
            updatedBy = snapshot.updatedBy,
            rowVersion = snapshot.rowVersion,
        )
    }
}

// ============================================================================
// Phase 4C.3 — per-patient encounter list envelope
// ============================================================================

/**
 * Wire envelope for
 * `GET /api/v1/tenants/{slug}/patients/{patientId}/encounters`
 * (Phase 4C.3).
 *
 * Un-paginated — see [ListPatientEncountersResult] KDoc. Adding
 * pagination later is additive (the envelope gains `totalCount`
 * / `limit` / `offset` fields; existing clients ignoring new
 * fields continue to work).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EncounterListResponse(
    val items: List<EncounterResponse>,
) {
    companion object {
        fun from(result: ListPatientEncountersResult): EncounterListResponse =
            EncounterListResponse(
                items = result.items.map { EncounterResponse.from(it) },
            )
    }
}

// ============================================================================
// Phase 4D.1 — encounter note DTOs (VS1 Chunk E)
// ============================================================================

/**
 * HTTP DTO for
 * `POST /api/v1/tenants/{slug}/encounters/{encounterId}/notes`
 * (Phase 4D.1, VS1 Chunk E).
 *
 * The body is PHI (free-text clinical content). The validator
 * at [com.medcore.clinical.encounter.write.CreateEncounterNoteValidator]
 * runs after Bean Validation and enforces trim-nonempty +
 * length ≤ 20,000 chars, matching V19's CHECK constraint.
 */
data class CreateEncounterNoteRequest(
    @field:NotNull
    val body: String?,
) {
    fun toCommand(slug: String, encounterId: UUID): CreateEncounterNoteCommand {
        val raw = body
            ?: throw WriteValidationException(field = "body", code = "required")
        return CreateEncounterNoteCommand(
            slug = slug,
            encounterId = encounterId,
            body = raw,
        )
    }
}

/**
 * Wire shape for encounter-note create + list responses
 * (Phase 4D.1, VS1 Chunk E).
 *
 * Body is PHI — served to the caller, never logged. The audit
 * row for the corresponding create / list call contains only
 * closed-enum tokens (see
 * [com.medcore.clinical.encounter.write.CreateEncounterNoteAuditor]
 * and
 * [com.medcore.clinical.encounter.read.ListEncounterNotesAuditor]).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EncounterNoteResponse(
    val id: UUID,
    val tenantId: UUID,
    val encounterId: UUID,
    val body: String,
    val status: EncounterNoteStatus,
    val signedAt: Instant?,
    val signedBy: UUID?,
    val amendsId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID,
    val updatedBy: UUID,
    val rowVersion: Long,
) {
    companion object {
        fun from(snapshot: EncounterNoteSnapshot): EncounterNoteResponse =
            EncounterNoteResponse(
                id = snapshot.id,
                tenantId = snapshot.tenantId,
                encounterId = snapshot.encounterId,
                body = snapshot.body,
                status = snapshot.status,
                signedAt = snapshot.signedAt,
                signedBy = snapshot.signedBy,
                amendsId = snapshot.amendsId,
                createdAt = snapshot.createdAt,
                updatedAt = snapshot.updatedAt,
                createdBy = snapshot.createdBy,
                updatedBy = snapshot.updatedBy,
                rowVersion = snapshot.rowVersion,
            )
    }
}

/**
 * Wire envelope for
 * `GET /api/v1/tenants/{slug}/encounters/{encounterId}/notes`
 * (Phase 4D.1).
 *
 * Un-paginated in 4D.1 (see [ListEncounterNotesResult] KDoc).
 * Adding pagination later is additive.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EncounterNoteListResponse(
    val items: List<EncounterNoteResponse>,
) {
    companion object {
        fun from(result: ListEncounterNotesResult): EncounterNoteListResponse =
            EncounterNoteListResponse(
                items = result.items.map { EncounterNoteResponse.from(it) },
            )
    }
}
