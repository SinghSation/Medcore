import type { PageInfo, PageResponse } from '@/lib/pagination'

/**
 * Wraps a list of items in the canonical paginated wire shape
 * `{ items, pageInfo }` so test mocks match the real server
 * envelope produced by every paginated list endpoint (ADR-009).
 *
 * Defaults to a single-page response (`hasNextPage: false`,
 * `nextCursor: null`) — the right shape for the overwhelming
 * majority of MVP fixtures, where the UI renders the first page
 * and never asks for a cursor.
 *
 * Pass `pageInfo` overrides to simulate a multi-page server
 * response (e.g., to test a future "Load more" surface).
 *
 * Lives next to `pagination.ts` rather than a generic test-utils
 * dump so the canonical shape is one symbol away from the type
 * it constructs. Production code must not import this module.
 */
export function pagedDataMock<T>(
  items: T[],
  pageInfo: Partial<PageInfo> = {},
): PageResponse<T> {
  return {
    items,
    pageInfo: {
      hasNextPage: pageInfo.hasNextPage ?? false,
      nextCursor: pageInfo.nextCursor ?? null,
    },
  }
}
