package com.medcore.clinical.encounter.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.medcore.clinical.encounter.model.CancelReason
import com.medcore.clinical.encounter.model.EncounterClass
import com.medcore.clinical.encounter.model.EncounterNoteStatus
import com.medcore.clinical.encounter.model.EncounterStatus
import com.medcore.clinical.encounter.read.ListEncounterNotesResult
import com.medcore.clinical.encounter.read.ListPatientEncountersResult
import com.medcore.clinical.encounter.write.AmendNoteCommand
import com.medcore.clinical.encounter.write.CancelEncounterCommand
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
    val cancelledAt: Instant?,
    val cancelReason: CancelReason?,
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
            cancelledAt = snapshot.cancelledAt,
            cancelReason = snapshot.cancelReason,
            createdAt = snapshot.createdAt,
            updatedAt = snapshot.updatedAt,
            createdBy = snapshot.createdBy,
            updatedBy = snapshot.updatedBy,
            rowVersion = snapshot.rowVersion,
        )
    }
}

// ============================================================================
// Phase 4C.5 — cancel encounter request
// ============================================================================

/**
 * HTTP DTO for
 * `POST /api/v1/tenants/{slug}/encounters/{encounterId}/cancel`
 * (Phase 4C.5).
 *
 * `cancelReason` on the wire is the closed-enum name (e.g.,
 * `"NO_SHOW"`). Unknown or missing values throw
 * [WriteValidationException] → 422
 * `request.validation_failed|format`.
 */
data class CancelEncounterRequest(
    @field:NotNull
    val cancelReason: String?,
) {
    fun toCommand(slug: String, encounterId: UUID): CancelEncounterCommand {
        val raw = cancelReason
            ?: throw WriteValidationException(
                field = "cancelReason",
                code = "required",
            )
        val parsed = try {
            enumValueOf<CancelReason>(raw)
        } catch (ex: IllegalArgumentException) {
            throw WriteValidationException(
                field = "cancelReason",
                code = "format",
                cause = ex,
            )
        }
        return CancelEncounterCommand(
            slug = slug,
            encounterId = encounterId,
            cancelReason = parsed,
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
 * HTTP DTO for
 * `POST /api/v1/tenants/{slug}/encounters/{encounterId}/notes/{noteId}/amend`
 * (Phase 4D.6).
 *
 * Same wire shape as [CreateEncounterNoteRequest] — a single
 * `body` field. The amendment is a complete, self-contained
 * note rather than a diff against the original; that keeps
 * audit + retrieval simple and matches FHIR
 * `DocumentReference.relatesTo: replaces` semantics.
 *
 * Validator at
 * [com.medcore.clinical.encounter.write.AmendNoteValidator]
 * enforces the same 1..20,000 char body bound as create-note,
 * matching V19's CHECK constraint.
 *
 * The `noteId` (the original being amended) and `encounterId`
 * come from the URL path, not the body — preventing
 * client-supplied amends_id mismatches and aligning with the
 * established controller pattern.
 */
data class AmendNoteRequest(
    @field:NotNull
    val body: String?,
) {
    fun toCommand(
        slug: String,
        encounterId: UUID,
        originalNoteId: UUID,
    ): AmendNoteCommand {
        val raw = body
            ?: throw WriteValidationException(field = "body", code = "required")
        return AmendNoteCommand(
            slug = slug,
            encounterId = encounterId,
            originalNoteId = originalNoteId,
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
 * (Phase 4D.1; paginated as of platform-pagination chunk B).
 *
 * Wire shape (per ADR-009 §2.4):
 *
 * ```json
 * {
 *   "data": {
 *     "items": [ ... ],
 *     "pageInfo": {
 *       "hasNextPage": true,
 *       "nextCursor": "eyJrIjoiY2xpbmljYWwuZW5jb3VudGVyX25vdGUudjEi..."
 *     }
 *   },
 *   "requestId": "..."
 * }
 * ```
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EncounterNoteListResponse(
    val items: List<EncounterNoteResponse>,
    val pageInfo: PageInfoDto,
) {
    companion object {
        fun from(result: ListEncounterNotesResult): EncounterNoteListResponse =
            EncounterNoteListResponse(
                items = result.items.map { EncounterNoteResponse.from(it) },
                pageInfo = PageInfoDto.from(result.pageInfo),
            )
    }
}

/**
 * Wire shape of the `pageInfo` field (ADR-009 §2.4). Mirrors
 * the substrate's [com.medcore.platform.read.pagination.PageInfo]
 * — carried as its own DTO so future per-resource extensions
 * (e.g., a `totalCount` opt-in for non-clinical surfaces)
 * don't ripple back into the platform substrate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PageInfoDto(
    val hasNextPage: Boolean,
    val nextCursor: String?,
) {
    companion object {
        fun from(pi: com.medcore.platform.read.pagination.PageInfo): PageInfoDto =
            PageInfoDto(hasNextPage = pi.hasNextPage, nextCursor = pi.nextCursor)
    }
}
