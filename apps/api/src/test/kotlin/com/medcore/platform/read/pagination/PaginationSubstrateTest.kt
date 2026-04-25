package com.medcore.platform.read.pagination

import com.medcore.platform.write.WriteValidationException
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test

/**
 * Locks the pagination substrate contract (ADR-009).
 *
 * Pure unit-level checks — no Spring context, no DB. The
 * substrate is a value-types-only API, so this test fully
 * exercises:
 *   - PageRequest input validation + defaults
 *   - PageInfo invariant (hasNextPage ↔ nextCursor agree)
 *   - PageResponse.fromFetchedPlusOne hasNext detection +
 *     trim + cursor extraction
 *   - Cursor round-trip via CursorCodec for both
 *     BucketedCursor and TimeCursor
 *   - CursorMalformedException for the four primary failure
 *     modes (bad base64, bad JSON, missing field, wrong `k`)
 */
class PaginationSubstrateTest {

    // ========================================================================
    // PageRequest
    // ========================================================================

    @Test
    fun `fromQueryParams applies defaults when both inputs are null`() {
        val req = PageRequest.fromQueryParams(pageSize = null, cursor = null)
        assertThat(req.pageSize).isEqualTo(PageRequest.DEFAULT_PAGE_SIZE)
        assertThat(req.cursor).isNull()
    }

    @Test
    fun `fromQueryParams treats blank cursor as absent`() {
        val req = PageRequest.fromQueryParams(pageSize = 10, cursor = "   ")
        assertThat(req.cursor).isNull()
    }

    @Test
    fun `fromQueryParams accepts pageSize at the boundaries`() {
        val low = PageRequest.fromQueryParams(pageSize = 1, cursor = null)
        val high = PageRequest.fromQueryParams(pageSize = 100, cursor = null)
        assertThat(low.pageSize).isEqualTo(1)
        assertThat(high.pageSize).isEqualTo(100)
    }

    @Test
    fun `fromQueryParams rejects pageSize below minimum with 422 out_of_range`() {
        assertThatExceptionOfType(WriteValidationException::class.java)
            .isThrownBy { PageRequest.fromQueryParams(pageSize = 0, cursor = null) }
            .matches { it.field == "pageSize" && it.code == "out_of_range" }
    }

    @Test
    fun `fromQueryParams rejects pageSize above maximum with 422 out_of_range`() {
        assertThatExceptionOfType(WriteValidationException::class.java)
            .isThrownBy { PageRequest.fromQueryParams(pageSize = 101, cursor = null) }
            .matches { it.field == "pageSize" && it.code == "out_of_range" }
    }

    // ========================================================================
    // PageInfo invariant
    // ========================================================================

    @Test
    fun `PageInfo refuses inconsistent (hasNextPage true, nextCursor null)`() {
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { PageInfo(hasNextPage = true, nextCursor = null) }
    }

    @Test
    fun `PageInfo refuses inconsistent (hasNextPage false, nextCursor non-null)`() {
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { PageInfo(hasNextPage = false, nextCursor = "tok") }
    }

    @Test
    fun `PageInfo LAST_PAGE sentinel is hasNextPage=false + nextCursor=null`() {
        assertThat(PageInfo.LAST_PAGE.hasNextPage).isFalse
        assertThat(PageInfo.LAST_PAGE.nextCursor).isNull()
    }

    // ========================================================================
    // PageResponse.fromFetchedPlusOne
    // ========================================================================

    @Test
    fun `fromFetchedPlusOne with exactly pageSize rows reports last page`() {
        val rows = listOf(1, 2, 3)
        val response = PageResponse.fromFetchedPlusOne(rows, pageSize = 3) {
            error("cursor builder must not be called when hasNextPage = false")
        }
        assertThat(response.items).containsExactly(1, 2, 3)
        assertThat(response.pageInfo).isEqualTo(PageInfo.LAST_PAGE)
    }

    @Test
    fun `fromFetchedPlusOne with pageSize+1 rows reports hasNextPage and trims`() {
        val rows = listOf(1, 2, 3, 4) // 4 = peek-ahead
        val response = PageResponse.fromFetchedPlusOne(rows, pageSize = 3) { last ->
            // Builder receives the LAST row of the trimmed page (3),
            // not the peek-ahead row (4). This is load-bearing —
            // the cursor encodes "where to resume" which is the
            // tail of the page, not the head of the next page.
            assertThat(last).isEqualTo(3)
            TimeCursor(
                k = "test.v1",
                ts = Instant.parse("2026-04-25T10:00:00Z"),
                id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                ascending = true,
            )
        }
        assertThat(response.items).containsExactly(1, 2, 3)
        assertThat(response.pageInfo.hasNextPage).isTrue
        assertThat(response.pageInfo.nextCursor).isNotNull
    }

    // ========================================================================
    // BucketedCursor round-trip
    // ========================================================================

    @Test
    fun `BucketedCursor round-trips through the codec`() {
        val original = BucketedCursor(
            k = "clinical.problem.v1",
            bucket = 2,
            ts = Instant.parse("2026-04-25T10:00:00Z"),
            id = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        )

        val token = CursorCodec.encode(original)
        // Token must NOT contain readable substance (defends
        // against accidental clear-text leakage in logs).
        assertThat(token).doesNotContain("clinical.problem")
        assertThat(token).doesNotContain("11111111")

        val map = CursorCodec.decodeMap(token)
        val decoded = BucketedCursor.fromMap(map, expectedKey = "clinical.problem.v1")
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `BucketedCursor rejects wrong k discriminator`() {
        val cursor = BucketedCursor(
            k = "clinical.allergy.v1",
            bucket = 0,
            ts = Instant.parse("2026-04-25T10:00:00Z"),
            id = UUID.randomUUID(),
        )
        val token = CursorCodec.encode(cursor)
        val map = CursorCodec.decodeMap(token)

        assertThatExceptionOfType(CursorMalformedException::class.java)
            .isThrownBy { BucketedCursor.fromMap(map, expectedKey = "clinical.problem.v1") }
    }

    // ========================================================================
    // TimeCursor round-trip
    // ========================================================================

    @Test
    fun `TimeCursor round-trips through the codec`() {
        val original = TimeCursor(
            k = "clinical.encounter.v1",
            ts = Instant.parse("2026-04-25T10:00:00Z"),
            id = UUID.fromString("11111111-2222-3333-4444-555555555555"),
            ascending = false,
        )

        val token = CursorCodec.encode(original)
        val map = CursorCodec.decodeMap(token)
        val decoded = TimeCursor.fromMap(map, expectedKey = "clinical.encounter.v1")
        assertThat(decoded).isEqualTo(original)
    }

    // ========================================================================
    // CursorMalformedException paths
    // ========================================================================

    @Test
    fun `CursorCodec throws CursorMalformedException for non-base64 input`() {
        assertThatExceptionOfType(CursorMalformedException::class.java)
            .isThrownBy { CursorCodec.decodeMap("!!!not-base64!!!") }
    }

    @Test
    fun `CursorCodec throws CursorMalformedException for valid base64 of non-JSON`() {
        // Base64 of the literal string "not json" — decodes fine
        // as bytes but isn't a JSON object.
        val token = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("not json".toByteArray())
        assertThatExceptionOfType(CursorMalformedException::class.java)
            .isThrownBy { CursorCodec.decodeMap(token) }
    }

    @Test
    fun `BucketedCursor fromMap throws on missing required field`() {
        val incomplete = mapOf<String, Any?>(
            "k" to "clinical.problem.v1",
            "b" to 0,
            // ts + id deliberately missing
        )
        assertThatExceptionOfType(CursorMalformedException::class.java)
            .isThrownBy { BucketedCursor.fromMap(incomplete, expectedKey = "clinical.problem.v1") }
    }

    @Test
    fun `TimeCursor fromMap throws on missing required field`() {
        val incomplete = mapOf<String, Any?>(
            "k" to "clinical.encounter.v1",
            "ts" to "2026-04-25T10:00:00Z",
            // id + asc deliberately missing
        )
        assertThatExceptionOfType(CursorMalformedException::class.java)
            .isThrownBy { TimeCursor.fromMap(incomplete, expectedKey = "clinical.encounter.v1") }
    }

    /**
     * Sister coverage to the missing-field tests above. A forged
     * or truncated cursor whose `ts` / `id` keys are *present*
     * but unparseable used to leak `DateTimeParseException` /
     * `IllegalArgumentException` from `Instant.parse` /
     * `UUID.fromString`, surfacing as a 500 instead of the
     * platform's 422 `cursor|malformed` envelope. The fromMap
     * decoders now wrap those parses; these tests prevent
     * regression.
     */
    @Test
    fun `BucketedCursor fromMap throws on unparseable ts`() {
        val bad = mapOf<String, Any?>(
            "k" to "clinical.problem.v1",
            "b" to 0,
            "ts" to "not-a-date",
            "id" to "00000000-0000-0000-0000-000000000001",
        )
        assertThatExceptionOfType(CursorMalformedException::class.java)
            .isThrownBy { BucketedCursor.fromMap(bad, expectedKey = "clinical.problem.v1") }
    }

    @Test
    fun `BucketedCursor fromMap throws on unparseable id`() {
        val bad = mapOf<String, Any?>(
            "k" to "clinical.problem.v1",
            "b" to 0,
            "ts" to "2026-04-25T10:00:00Z",
            "id" to "not-a-uuid",
        )
        assertThatExceptionOfType(CursorMalformedException::class.java)
            .isThrownBy { BucketedCursor.fromMap(bad, expectedKey = "clinical.problem.v1") }
    }

    @Test
    fun `TimeCursor fromMap throws on unparseable ts`() {
        val bad = mapOf<String, Any?>(
            "k" to "clinical.encounter.v1",
            "ts" to "not-a-date",
            "id" to "00000000-0000-0000-0000-000000000001",
            "asc" to false,
        )
        assertThatExceptionOfType(CursorMalformedException::class.java)
            .isThrownBy { TimeCursor.fromMap(bad, expectedKey = "clinical.encounter.v1") }
    }

    @Test
    fun `TimeCursor fromMap throws on unparseable id`() {
        val bad = mapOf<String, Any?>(
            "k" to "clinical.encounter.v1",
            "ts" to "2026-04-25T10:00:00Z",
            "id" to "not-a-uuid",
            "asc" to false,
        )
        assertThatExceptionOfType(CursorMalformedException::class.java)
            .isThrownBy { TimeCursor.fromMap(bad, expectedKey = "clinical.encounter.v1") }
    }

    /**
     * Coverage for the [CursorCodec] non-Map-JSON guard. Forged
     * tokens whose JSON body parses to an array, scalar, or null
     * used to be silently coerced by `readValue(Map::class.java)`;
     * they now surface as 422 `cursor|malformed`.
     */
    @Test
    fun `CursorCodec throws CursorMalformedException for JSON that is not an object`() {
        // Each token below decodes to legitimate base64 JSON, but
        // the JSON value is a non-object that the cursor contract
        // rejects.
        val arrayToken = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("[]".toByteArray())
        val numberToken = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("42".toByteArray())
        val stringToken = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("\"foo\"".toByteArray())
        val nullToken = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("null".toByteArray())

        listOf(arrayToken, numberToken, stringToken, nullToken).forEach { token ->
            assertThatExceptionOfType(CursorMalformedException::class.java)
                .isThrownBy { CursorCodec.decodeMap(token) }
        }
    }
}
