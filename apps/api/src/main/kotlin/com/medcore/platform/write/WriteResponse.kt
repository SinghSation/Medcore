package com.medcore.platform.write

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Uniform success envelope for every mutation endpoint in Medcore
 * (Phase 3J, ADR-007 §4.7).
 *
 * Mirrors the shape philosophy of
 * [com.medcore.platform.api.ErrorResponse] — a single wire-stable
 * shape every caller learns once. `requestId` parity with the error
 * envelope lets clients correlate a successful write with its
 * request id, matching the pattern already in `HeaderBodyRequestIdParityTest`.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WriteResponse<T>(
    val data: T,
    val requestId: String?,
)
