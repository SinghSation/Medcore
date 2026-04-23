package com.medcore.platform.api

/**
 * Signals that a conditional request (RFC 7232) is missing a
 * REQUIRED precondition header — specifically, `If-Match` on a
 * PHI-bearing PATCH (Phase 4A.2).
 *
 * ### Why 428, not 400 or 409
 *
 * RFC 6585 §3 defines 428 Precondition Required exactly for this:
 * "the server requires the request to be conditional." Existing
 * alternatives are worse:
 *
 *  - **400 Bad Request** — doesn't distinguish "missing header" from
 *    "unparseable body" or "wrong content-type". Clients can't
 *    programmatically recover.
 *  - **409 Conflict** — used by Medcore for stale-`If-Match`
 *    (`resource.conflict`, `details.reason = stale_row`). If 428
 *    also mapped to 409, clients couldn't distinguish "header
 *    missing" (retry with the latest `row_version`) from "header
 *    stale" (re-fetch the resource, extract new `ETag`, retry).
 *
 * ### Wire shape
 *
 * `GlobalExceptionHandler.onPreconditionRequired` maps to 428 with
 * `code = request.precondition_required` and a fixed message.
 * `details` is `null` per the envelope discipline.
 *
 * ### Scope
 *
 * 4A.2 enforces `If-Match` on every PHI PATCH endpoint. Future
 * PHI endpoints (4A.5 read, 4B appointments, etc.) will follow the
 * same rule. Non-PHI PATCH endpoints (e.g., 3J.2 tenant display
 * name) do NOT require `If-Match` — PHI is the bar, not PATCH.
 */
class PreconditionRequiredException(val headerName: String) : RuntimeException(
    "precondition header missing: $headerName",
)
