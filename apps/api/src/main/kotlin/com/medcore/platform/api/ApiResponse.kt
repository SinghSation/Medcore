package com.medcore.platform.api

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Canonical success envelope for EVERY Medcore API endpoint —
 * reads, writes, future bulk operations, FHIR passthroughs, etc.
 *
 * Introduced in Phase 3J (as `WriteResponse<T>`) and renamed in
 * Phase 4A.4 to reflect its use as the single wire-stable shape
 * across read and write endpoints. A single envelope prevents
 * fragmentation — no `ReadResponse<T>`, `BulkResponse<T>`, or
 * per-operation-type shapes.
 *
 * Mirrors the shape philosophy of
 * [com.medcore.platform.api.ErrorResponse]: one shape every
 * caller learns once. `requestId` parity with the error envelope
 * lets clients correlate a successful operation with its request
 * id (enforced by `HeaderBodyRequestIdParityTest`).
 *
 * ### Wire shape (NORMATIVE)
 *
 * ```json
 * { "data": { ... }, "requestId": "..." }
 * ```
 *
 * `@JsonInclude(NON_NULL)` omits `requestId` when null (e.g.,
 * non-HTTP code paths that reuse the envelope).
 *
 * ### Compatibility
 *
 * The rename is source-level only; the WIRE shape is unchanged
 * from `WriteResponse<T>`. Existing clients see identical JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val data: T,
    val requestId: String?,
)
