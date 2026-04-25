package com.medcore.platform.read.pagination

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64

/**
 * Encodes and decodes pagination cursors as opaque base64-encoded
 * JSON tokens (Phase platform-pagination, ADR-009).
 *
 * **Wire shape**:
 *
 *   `base64(json(cursor.toMap()))`
 *
 * Example (problems endpoint):
 *
 *   `eyJrIjoiY2xpbmljYWwucHJvYmxlbS52MSIsImIiOjAsInRzIjoiMjAyNi0w...`
 *
 * Decodes to:
 *
 *   `{"k":"clinical.problem.v1","b":0,"ts":"2026-04-25T10:00:00Z","id":"<uuid>"}`
 *
 * **Stateless object** — pure encoding, no per-resource knowledge.
 * Each resource provides its own [Cursor] implementation; the codec
 * just rounds-trips the [Cursor.toMap] / map → cursor pair.
 *
 * **URL-safe base64** (RFC 4648 §5) — `+` / `/` / `=` swapped for
 * `-` / `_` / no padding. Lets cursors travel in query strings
 * without percent-encoding.
 *
 * **Malformed cursor handling**: any failure during decode (bad
 * base64, bad JSON, missing required field, wrong `k`
 * discriminator, etc.) raises [CursorMalformedException], which
 * the platform exception handler maps to 422 `cursor|malformed`
 * (resource-specific decoders may raise the same exception with
 * a more specific code).
 */
object CursorCodec {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    /**
     * Encode a [Cursor] to its opaque wire token.
     */
    fun encode(cursor: Cursor): String {
        val json = mapper.writeValueAsBytes(cursor.toMap())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json)
    }

    /**
     * Decode a wire token to its raw map. Resource-specific
     * decoders read this map via their own `fromMap` companion
     * (e.g., [BucketedCursor.fromMap]) which performs the `k`
     * discriminator check and types each field.
     *
     * @throws CursorMalformedException when the token is not
     *   valid base64, its JSON payload is unparseable, or the
     *   parsed JSON is not an object (e.g., a forged token whose
     *   body parses to `[]`, `"foo"`, `null`, or a number).
     */
    @Suppress("UNCHECKED_CAST")
    fun decodeMap(token: String): Map<String, Any?> {
        val bytes = try {
            Base64.getUrlDecoder().decode(token)
        } catch (ex: IllegalArgumentException) {
            throw CursorMalformedException("not valid base64")
        }
        // Parse to a JsonNode first so non-object payloads (arrays,
        // scalars, null) are rejected explicitly rather than being
        // silently coerced by `readValue(Map::class.java)`. The
        // narrow type catches IO/parse failures from Jackson; a
        // truly unexpected exception (e.g., OOM) still propagates.
        val node = try {
            mapper.readTree(bytes)
        } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
            throw CursorMalformedException("not valid JSON: ${ex.javaClass.simpleName}")
        }
        if (node == null || !node.isObject) {
            throw CursorMalformedException("not a JSON object")
        }
        return mapper.convertValue(node, Map::class.java) as Map<String, Any?>
    }
}
