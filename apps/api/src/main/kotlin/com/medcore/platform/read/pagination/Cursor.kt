package com.medcore.platform.read.pagination

import java.time.Instant
import java.util.UUID

/**
 * Marker interface for any cursor type used by a paginated list
 * endpoint (Phase platform-pagination, ADR-009).
 *
 * **Cursor payload (opaque on the wire, transparent server-side):**
 *
 * Each cursor implementation `toMap()` produces a JSON-serialisable
 * map carrying:
 *
 *   - `k` — resource version discriminator (e.g.,
 *     `"clinical.problem.v1"`). Lets the server detect stale-format
 *     cursors when a future schema evolution changes the tuple.
 *   - The full sort tuple of the LAST row in the previous page —
 *     EVERY component the ordering uses, not just the timestamp.
 *     For composite sorts (e.g., problems' `(status_priority,
 *     createdAt DESC, id)`) all three components must be present.
 *
 * Two helper implementations cover the existing endpoints:
 *
 *   - [BucketedCursor] — composite sort: `(bucket: Int, ts: Instant, id: UUID)`.
 *     Used by problems + allergies (status-priority bucket + timestamp).
 *   - [TimeCursor] — simple time-based sort: `(ts: Instant, id: UUID)`.
 *     Used by encounters + encounter-notes.
 *
 * Resources can also implement [Cursor] directly if their sort axis
 * doesn't fit either helper.
 *
 * **Why the cursor must encode the FULL sort tuple, not just the
 * last value + id:** for composite sorts, encoding only `(ts, id)`
 * would break across status buckets — the server can't tell which
 * bucket a `ts` belongs to and can't construct the correct `WHERE`
 * clause to advance past it. ADR-009 §2.2.
 */
interface Cursor {
    /**
     * Stable resource-version discriminator. Future cursor format
     * changes bump this (`v1` → `v2`) so a v2 server can detect +
     * reject (or migrate) stale v1 cursors with 422.
     */
    val k: String

    /**
     * Serialise to a JSON-encodable map. The codec base64-encodes
     * the JSON. Implementations include `k` plus every sort tuple
     * component.
     */
    fun toMap(): Map<String, Any>
}

/**
 * Composite-sort cursor for "buckets-then-time" orderings.
 *
 * Used by problems + allergies. The integer `bucket` encodes the
 * status priority (ACTIVE=0, INACTIVE=1, RESOLVED=2,
 * ENTERED_IN_ERROR=3 per ADR-009 §2.5 — load-bearing for the
 * RESOLVED ≠ INACTIVE invariant).
 *
 * The natural sort is bucket ASC, then `createdAt DESC`, then `id`
 * ASC for tie-break.
 */
data class BucketedCursor(
    override val k: String,
    val bucket: Int,
    val ts: Instant,
    val id: UUID,
) : Cursor {
    override fun toMap(): Map<String, Any> = mapOf(
        "k" to k,
        "b" to bucket,
        "ts" to ts.toString(),
        "id" to id.toString(),
    )

    companion object {
        /** Decode helper for handlers — call from inside the
         *  resource-specific `cursorFromMap` handler shim. */
        fun fromMap(map: Map<String, Any?>, expectedKey: String): BucketedCursor {
            val k = map["k"] as? String
                ?: throw CursorMalformedException("missing 'k'")
            if (k != expectedKey) {
                throw CursorMalformedException("expected k='$expectedKey', got '$k'")
            }
            val b = (map["b"] as? Number)?.toInt()
                ?: throw CursorMalformedException("missing 'b'")
            val ts = parseInstant(map["ts"], "ts")
            val id = parseUuid(map["id"], "id")
            return BucketedCursor(k = k, bucket = b, ts = ts, id = id)
        }
    }
}

/**
 * Time-only cursor for "newest-first" or "oldest-first" orderings.
 *
 * Used by encounters (newest first) + encounter-notes (oldest
 * first). `ascending` documents the intended sort direction so a
 * future audit / replay tool can interpret the cursor without
 * joining the schema.
 */
data class TimeCursor(
    override val k: String,
    val ts: Instant,
    val id: UUID,
    val ascending: Boolean,
) : Cursor {
    override fun toMap(): Map<String, Any> = mapOf(
        "k" to k,
        "ts" to ts.toString(),
        "id" to id.toString(),
        "asc" to ascending,
    )

    companion object {
        fun fromMap(map: Map<String, Any?>, expectedKey: String): TimeCursor {
            val k = map["k"] as? String
                ?: throw CursorMalformedException("missing 'k'")
            if (k != expectedKey) {
                throw CursorMalformedException("expected k='$expectedKey', got '$k'")
            }
            val ts = parseInstant(map["ts"], "ts")
            val id = parseUuid(map["id"], "id")
            val asc = map["asc"] as? Boolean
                ?: throw CursorMalformedException("missing 'asc'")
            return TimeCursor(k = k, ts = ts, id = id, ascending = asc)
        }
    }
}

/**
 * Thrown by cursor decoding when the token is malformed,
 * unparseable, or carries a stale-format `k` discriminator.
 *
 * Mapped to 422 `cursor|malformed` (or `cursor|stale_format` for a
 * known-but-unsupported `k`) by the platform exception handler —
 * NOT a 409 (state conflict) and NOT a 404 (resource missing).
 * It's a parsing error.
 */
class CursorMalformedException(reason: String) : RuntimeException(reason)

/**
 * Decode helpers shared by [BucketedCursor.fromMap] and
 * [TimeCursor.fromMap]. They convert *both* "missing key" AND
 * "key present but unparseable" into [CursorMalformedException]
 * — the only outcome the platform exception handler maps to
 * 422 `cursor|malformed`. Without these wrappers a forged or
 * truncated cursor with a String `ts`/`id` that fails
 * [Instant.parse] / [UUID.fromString] would propagate the raw
 * `DateTimeParseException` / `IllegalArgumentException`,
 * surfacing as a generic 500.
 */
private fun parseInstant(raw: Any?, field: String): Instant {
    val str = raw as? String
        ?: throw CursorMalformedException("missing '$field'")
    return try {
        Instant.parse(str)
    } catch (ex: java.time.format.DateTimeParseException) {
        throw CursorMalformedException("unparseable '$field'")
    }
}

private fun parseUuid(raw: Any?, field: String): UUID {
    val str = raw as? String
        ?: throw CursorMalformedException("missing '$field'")
    return try {
        UUID.fromString(str)
    } catch (ex: IllegalArgumentException) {
        throw CursorMalformedException("unparseable '$field'")
    }
}
