package com.medcore.clinical.encounter.api

import com.medcore.clinical.encounter.read.GetEncounterCommand
import com.medcore.clinical.encounter.read.GetEncounterHandler
import com.medcore.clinical.encounter.read.ListEncounterNotesCommand
import com.medcore.clinical.encounter.read.ListEncounterNotesHandler
import com.medcore.clinical.encounter.read.ListEncounterNotesResult
import com.medcore.clinical.encounter.write.CreateEncounterNoteCommand
import com.medcore.clinical.encounter.write.CreateEncounterNoteHandler
import com.medcore.clinical.encounter.write.EncounterNoteSnapshot
import com.medcore.clinical.encounter.write.EncounterSnapshot
import com.medcore.clinical.encounter.write.StartEncounterCommand
import com.medcore.clinical.encounter.write.StartEncounterHandler
import com.medcore.platform.api.ApiResponse
import com.medcore.platform.observability.MdcKeys
import com.medcore.platform.read.ReadGate
import com.medcore.platform.security.MedcorePrincipal
import com.medcore.platform.write.WriteContext
import com.medcore.platform.write.WriteGate
import jakarta.validation.Valid
import java.util.UUID
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP surface for `clinical.encounter` (Phase 4C.1, VS1 Chunk D).
 *
 * First clinical WRITE that is NOT a patient-demographic write.
 * Two endpoints ship in this chunk:
 *
 *   - `POST /api/v1/tenants/{slug}/patients/{patientId}/encounters`
 *     — start an encounter (status = IN_PROGRESS)
 *   - `GET  /api/v1/tenants/{slug}/encounters/{encounterId}`
 *     — read an encounter
 *
 * POST is patient-scoped in the URL because encounters are always
 * owned by a patient — the path makes that relationship explicit.
 * GET is tenant-scoped (no patient in the URL) because given an
 * encounter id, the backend derives the patient via FK.
 *
 * ### Wire contract
 *
 * POST 201 body: `{data: EncounterResponse, requestId}` +
 * `ETag: "<rowVersion>"` (rowVersion = 0 for a fresh row, but
 * present for the future UPDATE slice).
 *
 * GET 200 body: same shape.
 *
 * 404 paths (unknown encounter, cross-tenant encounter, unknown
 * patient on POST, cross-tenant patient on POST) are identical —
 * no existence leak.
 *
 * ### Authority gates
 *
 * - POST requires `ENCOUNTER_WRITE` (OWNER/ADMIN per 4C.1 map).
 * - GET requires `ENCOUNTER_READ` (all three roles per 4C.1 map).
 */
@RestController
class EncounterController(
    private val startEncounterGate: WriteGate<StartEncounterCommand, EncounterSnapshot>,
    private val startEncounterHandler: StartEncounterHandler,
    private val getEncounterGate: ReadGate<GetEncounterCommand, EncounterSnapshot>,
    private val getEncounterHandler: GetEncounterHandler,
    // --- 4D.1 encounter notes (VS1 Chunk E) ---
    private val createEncounterNoteGate:
        WriteGate<CreateEncounterNoteCommand, EncounterNoteSnapshot>,
    private val createEncounterNoteHandler: CreateEncounterNoteHandler,
    private val listEncounterNotesGate:
        ReadGate<ListEncounterNotesCommand, ListEncounterNotesResult>,
    private val listEncounterNotesHandler: ListEncounterNotesHandler,
) {

    /**
     * Start an encounter for a patient (Phase 4C.1). PHI-handling
     * write. Emits `CLINICAL_ENCOUNTER_STARTED` on 200.
     */
    @PostMapping(
        "/api/v1/tenants/{slug}/patients/{patientId}/encounters",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun startEncounter(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable patientId: UUID,
        @Valid @RequestBody body: StartEncounterRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<ApiResponse<EncounterResponse>> {
        val command = body.toCommand(slug, patientId)
        val context = WriteContext(principal = principal, idempotencyKey = idempotencyKey)
        val snapshot = startEncounterGate.apply(command, context) { cmd ->
            startEncounterHandler.handle(cmd, context)
        }
        val responseBody = ApiResponse(
            data = EncounterResponse.from(snapshot),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag("\"${snapshot.rowVersion}\"")
            .body(responseBody)
    }

    /**
     * Read an encounter (Phase 4C.1). PHI read. Emits
     * `CLINICAL_ENCOUNTER_ACCESSED` on 200.
     */
    @GetMapping(
        "/api/v1/tenants/{slug}/encounters/{encounterId}",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getEncounter(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable encounterId: UUID,
    ): ResponseEntity<ApiResponse<EncounterResponse>> {
        val command = GetEncounterCommand(slug = slug, encounterId = encounterId)
        val context = WriteContext(principal = principal, idempotencyKey = null)
        val snapshot = getEncounterGate.apply(command, context) { cmd ->
            getEncounterHandler.handle(cmd, context)
        }
        val responseBody = ApiResponse(
            data = EncounterResponse.from(snapshot),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
        return ResponseEntity.ok()
            .eTag("\"${snapshot.rowVersion}\"")
            .body(responseBody)
    }

    // ========================================================================
    // Phase 4D.1 — encounter notes (VS1 Chunk E)
    // ========================================================================

    /**
     * Create a clinical note tied to an encounter (Phase 4D.1).
     * Append-only — every call mints a new row. Emits
     * `CLINICAL_ENCOUNTER_NOTE_CREATED` on 201. Requires
     * `NOTE_WRITE` (OWNER/ADMIN).
     */
    @PostMapping(
        "/api/v1/tenants/{slug}/encounters/{encounterId}/notes",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun createEncounterNote(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable encounterId: UUID,
        @Valid @RequestBody body: CreateEncounterNoteRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<ApiResponse<EncounterNoteResponse>> {
        val command = body.toCommand(slug, encounterId)
        val context = WriteContext(principal = principal, idempotencyKey = idempotencyKey)
        val snapshot = createEncounterNoteGate.apply(command, context) { cmd ->
            createEncounterNoteHandler.handle(cmd, context)
        }
        val responseBody = ApiResponse(
            data = EncounterNoteResponse.from(snapshot),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag("\"${snapshot.rowVersion}\"")
            .body(responseBody)
    }

    /**
     * List all notes for an encounter, newest first (Phase 4D.1).
     * Emits `CLINICAL_ENCOUNTER_NOTE_LIST_ACCESSED` on 200,
     * including for empty lists. Requires `NOTE_READ` (all
     * three roles).
     */
    @GetMapping(
        "/api/v1/tenants/{slug}/encounters/{encounterId}/notes",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun listEncounterNotes(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable slug: String,
        @PathVariable encounterId: UUID,
    ): ResponseEntity<ApiResponse<EncounterNoteListResponse>> {
        val command = ListEncounterNotesCommand(slug = slug, encounterId = encounterId)
        val context = WriteContext(principal = principal, idempotencyKey = null)
        val result = listEncounterNotesGate.apply(command, context) { cmd ->
            listEncounterNotesHandler.handle(cmd, context)
        }
        val responseBody = ApiResponse(
            data = EncounterNoteListResponse.from(result),
            requestId = MDC.get(MdcKeys.REQUEST_ID),
        )
        return ResponseEntity.ok(responseBody)
    }
}
