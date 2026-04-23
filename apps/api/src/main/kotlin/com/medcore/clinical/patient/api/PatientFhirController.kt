package com.medcore.clinical.patient.api

import com.medcore.clinical.patient.fhir.FhirPatient
import com.medcore.clinical.patient.fhir.PatientFhirMapper
import com.medcore.clinical.patient.read.GetPatientCommand
import com.medcore.clinical.patient.read.GetPatientHandler
import com.medcore.clinical.patient.write.PatientSnapshot
import com.medcore.platform.observability.MdcKeys
import com.medcore.platform.read.ReadGate
import com.medcore.platform.security.MedcorePrincipal
import com.medcore.platform.write.WriteContext
import com.medcore.tenancy.context.TenantContext
import com.medcore.tenancy.context.TenantContextMissingException
import java.util.UUID
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * FHIR R4 HTTP surface for the `clinical.patient` resource
 * (Phase 4A.5).
 *
 * ### Why a separate controller (not appended to PatientController)
 *
 * `PatientController` lives under the Medcore internal HTTP
 * namespace (tenant-scoped `api v1 tenants slug patients`
 * path). FHIR endpoints live under the interop namespace
 * (`fhir r4` path tree). Mixing the two in one class would
 * require Spring MVC contortions with class-level
 * `@RequestMapping` prefixes; a dedicated controller is both
 * simpler and makes the FHIR surface easy to find + govern.
 *
 * ### What 4A.5 ships
 *
 * One endpoint: GET on `fhir/r4/Patient/{patientId}`.
 *
 * Response: a bare FHIR R4 `Patient` resource (JSON). **NOT
 * wrapped in [com.medcore.platform.api.ApiResponse]** — FHIR
 * spec owns the response wire shape. Correlation travels in
 * the `X-Request-Id` HTTP header.
 *
 * This is a documented deliberate exception to the canonical-
 * envelope rule in `clinical-write-pattern.md` §12.6; see
 * that section for the narrow scope (FHIR namespace only)
 * and the rationale.
 *
 * ### What 4A.5 does NOT ship (scope bar)
 *
 *   - `GET fhir/r4/Patient?...` search
 *   - `Bundle` response wrapping
 *   - Other FHIR resources (Observation, Encounter, etc.)
 *   - FHIR `CapabilityStatement` / SMART launch
 *   - HAPI FHIR library integration
 *   - Full US Core Patient v6.x profile conformance. This is
 *     a **minimal FHIR R4 Patient mapping** — US Core–
 *     influenced but not profile-conformant; see
 *     [FhirPatient] KDoc for the authoritative scope.
 *
 * ### Security + audit envelope (reused from 4A.4)
 *
 * - Spring Security authenticates via the same JWT filter
 *   chain that covers the `api` namespace. The 4A.5 filter-
 *   config extension brings the FHIR namespace into the same
 *   security matcher.
 * - `TenantContextFilter` resolves the `X-Medcore-Tenant`
 *   header (filter coverage extended to FHIR paths in 4A.5).
 * - `PhiRequestContextFilter` populates
 *   `PhiRequestContextHolder` from principal + tenant.
 * - `MdcUserIdFilter` populates MDC (same filter-coverage
 *   extension).
 * - `ReadGate` opens the transaction and runs
 *   `PhiRlsTxHook` then `GetPatientHandler` then
 *   `GetPatientAuditor`. Audit emission uses
 *   `CLINICAL_PATIENT_ACCESSED` — same action as the 4A.4
 *   native-JSON path; PHI disclosure is PHI disclosure
 *   regardless of wire shape.
 *
 * ### Tenant-slug resolution
 *
 * The existing `GetPatientCommand` + handler shape takes a
 * tenant slug. FHIR URLs don't carry the slug — it comes via
 * `X-Medcore-Tenant`. The controller reads the resolved
 * tenant context via [TenantContext.current] and pulls the
 * slug from there.
 */
@RestController
@RequestMapping("/fhir/r4/Patient")
class PatientFhirController(
    private val getPatientGate: ReadGate<GetPatientCommand, PatientSnapshot>,
    private val getPatientHandler: GetPatientHandler,
    private val patientFhirMapper: PatientFhirMapper,
    private val tenantContext: TenantContext,
) {

    /**
     * Read a patient as a FHIR R4 Patient resource
     * (Phase 4A.5). Returns a bare resource body (no
     * envelope wrapping) per FHIR wire spec.
     *
     * Response headers:
     *   - `ETag: "<rowVersion>"` — same semantics as 4A.4
     *   - `X-Request-Id: <uuid>` — correlation substitute
     *     for the canonical-envelope `requestId` field that
     *     would normally live in the response body
     */
    @GetMapping(
        "/{patientId}",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getPatient(
        @AuthenticationPrincipal principal: MedcorePrincipal,
        @PathVariable patientId: UUID,
    ): ResponseEntity<FhirPatient> {
        // Tenant is resolved from the X-Medcore-Tenant header
        // by TenantContextFilter before this method runs. If
        // the header is absent, there's no tenant context and
        // the FHIR read cannot proceed — throw the standard
        // TenantContextMissingException → 422.
        val resolved = tenantContext.current()
            ?: throw TenantContextMissingException(
                "X-Medcore-Tenant header is required for FHIR reads",
            )

        val command = GetPatientCommand(slug = resolved.tenantSlug, patientId = patientId)
        val context = WriteContext(principal = principal, idempotencyKey = null)
        val snapshot = getPatientGate.apply(command, context) { cmd ->
            getPatientHandler.handle(cmd, context)
        }

        val fhirResource = patientFhirMapper.toFhir(snapshot)
        return ResponseEntity.ok()
            .eTag("\"${snapshot.rowVersion}\"")
            .header(REQUEST_ID_HEADER, MDC.get(MdcKeys.REQUEST_ID))
            .contentType(MediaType.APPLICATION_JSON)
            .body(fhirResource)
    }

    private companion object {
        /**
         * Correlation substitute for the canonical envelope's
         * `requestId` field. Matches
         * [com.medcore.platform.observability.RequestIdFilter]'s
         * response-header discipline.
         */
        const val REQUEST_ID_HEADER: String = "X-Request-Id"
    }
}
