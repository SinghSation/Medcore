package com.medcore.platform.read.pagination

/**
 * Substrate response shape for any paginated list endpoint
 * (Phase platform-pagination, ADR-009).
 *
 * Wire envelope (after wrapping in the platform `ApiResponse`):
 *
 * ```json
 * {
 *   "data": {
 *     "items": [ ... ],
 *     "pageInfo": {
 *       "hasNextPage": true,
 *       "nextCursor": "eyJrIjoiY2xpbmljYWwucHJvYmxlbS52MSIs..."
 *     }
 *   },
 *   "requestId": "..."
 * }
 * ```
 *
 * **No `totalCount`** — see ADR-009 §2.4. Clients drive iteration
 * off `hasNextPage`; computing total is a separate, optional
 * forensic surface.
 *
 * `hasNextPage = false` AND `nextCursor = null` indicates the last
 * page. Calling with a stale cursor when `hasNextPage` was false
 * yields an empty page, NOT an error.
 */
data class PageResponse<T>(
    val items: List<T>,
    val pageInfo: PageInfo,
) {
    companion object {
        /**
         * Helper for the common "we fetched `pageSize + 1` rows
         * to detect hasNext" handler pattern. The handler loads
         * one extra row beyond the page; if it exists, build the
         * cursor from the LAST row of the actual page (not the
         * peeked extra row) and trim the result.
         *
         * @param fetchedPlusOne the rows returned by the repo
         *   (may be exactly `pageSize` if no further data exists,
         *   or `pageSize + 1` if more data is available).
         * @param pageSize the requested page size.
         * @param cursorOf called only when `hasNextPage = true`,
         *   on the LAST row of the trimmed page (index pageSize-1).
         */
        fun <T> fromFetchedPlusOne(
            fetchedPlusOne: List<T>,
            pageSize: Int,
            cursorOf: (T) -> Cursor,
        ): PageResponse<T> {
            val hasNextPage = fetchedPlusOne.size > pageSize
            val pageItems = if (hasNextPage) fetchedPlusOne.take(pageSize) else fetchedPlusOne
            val nextCursor = if (hasNextPage) {
                CursorCodec.encode(cursorOf(pageItems.last()))
            } else {
                null
            }
            return PageResponse(
                items = pageItems,
                pageInfo = PageInfo(
                    hasNextPage = hasNextPage,
                    nextCursor = nextCursor,
                ),
            )
        }
    }
}
