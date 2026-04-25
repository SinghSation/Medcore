/**
 * Pagination substrate (ADR-009).
 *
 * Mirrors the backend `read.pagination` package. Every list
 * endpoint (problems, allergies, encounters, encounter-notes)
 * uses this single envelope and these defaults.
 *
 * **Cursors are opaque to the frontend.** Treat the `nextCursor`
 * string returned by the server as a black-box token: pass it
 * back verbatim as the `?cursor=` query parameter to fetch the
 * next page. Never parse, decode, or compose cursor tokens —
 * the server is the sole authority over their format.
 */

export const DEFAULT_PAGE_SIZE = 50
export const MIN_PAGE_SIZE = 1
export const MAX_PAGE_SIZE = 100

/**
 * Wire shape of the `pageInfo` field returned by every paginated
 * list endpoint.
 *
 * - `hasNextPage = true`  → `nextCursor` is non-null; pass it as
 *   `?cursor=` to fetch the next page.
 * - `hasNextPage = false` → this is the last page; `nextCursor`
 *   is null. Stop iterating.
 *
 * The two fields agree by construction (server-side invariant).
 */
export interface PageInfo {
  hasNextPage: boolean
  nextCursor: string | null
}

/**
 * Wire shape of every paginated list response (the `data` payload
 * of the platform `ApiResponse` envelope).
 *
 * `T` is the resource shape (e.g., `Problem`, `Allergy`,
 * `Encounter`, `EncounterNote`).
 */
export interface PageResponse<T> {
  items: T[]
  pageInfo: PageInfo
}

/**
 * Caller-side input for a paginated GET. Both fields are
 * optional; defaults apply server-side.
 *
 *   - omitting both → first page, default size (50).
 *   - `pageSize`    → bounded [1..100]; out-of-range → 422.
 *   - `cursor`      → opaque token returned by a prior
 *                     `pageInfo.nextCursor`. Stale cursors
 *                     decoded by the server raise 422
 *                     `cursor|malformed`.
 */
export interface PageRequest {
  pageSize?: number
  cursor?: string
}

/**
 * Build URLSearchParams for the pagination query string. Used by
 * each domain's API-client lib so the wire encoding stays in one
 * place.
 *
 * Empty / undefined values are omitted (no `?pageSize=` or
 * `?cursor=` appears) so the server sees the same shape as a
 * caller who never set the field. This matches the backend's
 * "optional → default" semantics.
 */
export function pageRequestToQuery(req: PageRequest | undefined): URLSearchParams {
  const params = new URLSearchParams()
  if (req?.pageSize !== undefined) {
    params.set('pageSize', String(req.pageSize))
  }
  if (req?.cursor !== undefined && req.cursor !== '') {
    params.set('cursor', req.cursor)
  }
  return params
}

/**
 * Helper for the common "render first page only" UX surface used
 * by Phase 4E.x cards (AllergyBanner, ProblemsCard) — extracts
 * `items` from a paged response with no `pageInfo` consumption.
 *
 * Card surfaces in MVP do NOT consume `pageInfo`: they render
 * whatever fits on the first page (default 50, well above
 * clinical norm for ACTIVE rows). A future "load more" surface
 * would consume `pageInfo.nextCursor` directly via
 * `useInfiniteQuery` — this helper does not preclude that.
 */
export function firstPageItems<T>(response: PageResponse<T>): T[] {
  return response.items
}
