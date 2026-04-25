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
     *   valid base64 or its JSON payload is unparseable.
     */
    @Suppress("UNCHECKED_CAST")
    fun decodeMap(token: String): Map<String, Any?> {
        val bytes = try {
            Base64.getUrlDecoder().decode(token)
        } catch (ex: IllegalArgumentException) {
            throw CursorMalformedException("not valid base64")
        }
        return try {
            mapper.readValue(bytes, Map::class.java) as Map<String, Any?>
        } catch (ex: Exception) {
            throw CursorMalformedException("not valid JSON: ${ex.javaClass.simpleName}")
        }
    }
}
