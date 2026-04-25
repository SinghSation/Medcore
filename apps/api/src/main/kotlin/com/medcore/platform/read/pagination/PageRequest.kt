package com.medcore.platform.read.pagination

import com.medcore.platform.write.WriteValidationException

/**
 * Caller-supplied pagination input for any list endpoint
 * (Phase platform-pagination, ADR-009).
 *
 * Two query parameters surface this:
 *
 * | Parameter  | Type   | Default | Bounds      | Notes                                        |
 * | ---------- | ------ | ------- | ----------- | -------------------------------------------- |
 * | `pageSize` | Int    | 50      | 1..100      | 422 `pageSize\|out_of_range` if outside.     |
 * | `cursor`   | String | null    | opaque B64  | First page when null. 422 if malformed.      |
 *
 * Construction goes through [fromQueryParams] so the bounds + cursor
 * format are always validated at the edge — handlers receive a
 * [PageRequest] that is already sane.
 *
 * **Cursors are opaque to clients.** Server-side code is the only
 * authority over cursor encoding; clients MUST NOT parse, construct,
 * or compose cursor tokens. Stale or wrong-format cursors yield
 * 422 `cursor|malformed` (not 409, not 404 — they're a parsing
 * failure, not a state conflict).
 */
data class PageRequest(
    val pageSize: Int,
    val cursor: String?,
) {

    init {
        require(pageSize in MIN_PAGE_SIZE..MAX_PAGE_SIZE) {
            "pageSize $pageSize outside [$MIN_PAGE_SIZE..$MAX_PAGE_SIZE]"
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50
        const val MIN_PAGE_SIZE: Int = 1
        const val MAX_PAGE_SIZE: Int = 100

        /**
         * Builds a [PageRequest] from the raw `?pageSize=N&cursor=...`
         * query parameters. Either may be null (caller omitted it);
         * defaults apply.
         *
         * @throws WriteValidationException 422 `pageSize|out_of_range`
         *   when `pageSize` is non-null and outside [MIN_PAGE_SIZE..MAX_PAGE_SIZE].
         *
         * Cursor format validation is the cursor codec's job — this
         * builder accepts any non-empty string and lets the handler
         * decode it (so the handler can return resource-specific
         * 422s, e.g., `cursor|stale_format` vs `cursor|malformed`).
         */
        fun fromQueryParams(pageSize: Int?, cursor: String?): PageRequest {
            val resolvedSize = pageSize ?: DEFAULT_PAGE_SIZE
            if (resolvedSize !in MIN_PAGE_SIZE..MAX_PAGE_SIZE) {
                throw WriteValidationException(
                    field = "pageSize",
                    code = "out_of_range",
                )
            }
            // Empty-string cursor is treated as absent (some clients
            // serialise null as empty). Whitespace-only is also
            // treated as absent.
            val resolvedCursor = cursor?.takeIf { it.isNotBlank() }
            return PageRequest(pageSize = resolvedSize, cursor = resolvedCursor)
        }

        /**
         * Sentinel for "default first page" — pageSize = default,
         * no cursor. Useful for tests + internal callers.
         */
        val DEFAULT_FIRST_PAGE: PageRequest = PageRequest(
            pageSize = DEFAULT_PAGE_SIZE,
            cursor = null,
        )
    }
}
