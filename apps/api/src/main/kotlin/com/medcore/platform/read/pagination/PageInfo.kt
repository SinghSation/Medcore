package com.medcore.platform.read.pagination

/**
 * Pagination metadata returned alongside `items` (ADR-009).
 *
 * `hasNextPage`:
 *   - `true`  → at least one more page exists; `nextCursor` is non-null.
 *   - `false` → this is the last page; `nextCursor` MUST be null.
 *
 * `nextCursor`:
 *   - Opaque base64 string (server-encoded; clients MUST treat it
 *     as a black-box token).
 *   - Pass back as the `?cursor=` query parameter to fetch the next page.
 *   - Stable shape — clients can compare for equality but MUST NOT
 *     parse, decode, or compose.
 */
data class PageInfo(
    val hasNextPage: Boolean,
    val nextCursor: String?,
) {
    init {
        // Invariant: hasNextPage and nextCursor agree. A nullable
        // nextCursor without hasNextPage = false would silently drop
        // pagination state for the caller.
        require(hasNextPage == (nextCursor != null)) {
            "hasNextPage ($hasNextPage) and nextCursor (${nextCursor != null}) must agree"
        }
    }

    companion object {
        /** Sentinel "no more pages" — used by handlers when the result fits in one page. */
        val LAST_PAGE: PageInfo = PageInfo(hasNextPage = false, nextCursor = null)
    }
}
