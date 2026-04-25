import { apiFetch } from '@/lib/api-client'

export type EncounterNoteStatus = 'DRAFT' | 'SIGNED'

/**
 * Wire shape of `EncounterNoteResponse` from
 * `POST /encounters/{id}/notes`,
 * `GET /encounters/{id}/notes` list items, and
 * `POST /notes/{id}/sign` (Phase 4D.5). Body is PHI — never log,
 * never persist client-side.
 *
 * 4D.5 fields (optional on the wire; `@JsonInclude(NON_NULL)`
 * strips them when unset):
 *   - `status` — `DRAFT` or `SIGNED`. Always present on the
 *     wire post-4D.5; optional in the type for defensive
 *     decoding of any hypothetical pre-4D.5 cache entries.
 *   - `signedAt` / `signedBy` — present ⇔ status = SIGNED.
 *   - `amendsId` — set on amendment rows that reference an
 *     original SIGNED note (Phase 4D.6). Originals and DRAFT
 *     non-amendments leave it absent.
 */
export interface EncounterNote {
  id: string
  tenantId: string
  encounterId: string
  body: string
  status?: EncounterNoteStatus
  signedAt?: string
  signedBy?: string
  amendsId?: string
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  rowVersion: number
}

/**
 * Wire shape of `EncounterNoteListResponse` (paginated as of
 * platform-pagination chunk B, ADR-009).
 *
 * Card surfaces in MVP render the first page only; future
 * "load more" UX would consume `pageInfo.nextCursor` directly.
 */
export interface EncounterNoteList {
  items: EncounterNote[]
  pageInfo: import('./pagination').PageInfo
}

export interface CreateEncounterNoteParams {
  tenantSlug: string
  encounterId: string
  body: string
  signal?: AbortSignal
}

export async function createEncounterNote(
  params: CreateEncounterNoteParams,
): Promise<EncounterNote> {
  const { tenantSlug, encounterId, body, signal } = params
  return apiFetch<EncounterNote>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/encounters/${encodeURIComponent(encounterId)}/notes`,
    {
      method: 'POST',
      body: { body },
      tenantSlug,
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}

export interface ListEncounterNotesParams {
  tenantSlug: string
  encounterId: string
  signal?: AbortSignal
}

export async function listEncounterNotes(
  params: ListEncounterNotesParams,
): Promise<EncounterNoteList> {
  const { tenantSlug, encounterId, signal } = params
  return apiFetch<EncounterNoteList>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/encounters/${encodeURIComponent(encounterId)}/notes`,
    {
      tenantSlug,
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}

export interface SignEncounterNoteParams {
  tenantSlug: string
  encounterId: string
  noteId: string
  signal?: AbortSignal
}

/**
 * Sign a note (Phase 4D.5). POST to `/sign` — HL7 FHIR action-style
 * URL for state transitions. No request body.
 *
 * - 200 → returns the signed `EncounterNote` with `status:
 *   'SIGNED'` and populated `signedAt` / `signedBy`.
 * - 403 → caller lacks `NOTE_SIGN`.
 * - 409 `resource.conflict` with `details.reason:
 *   note_already_signed` → note is already signed. The UI
 *   refetches the list on this path to reconcile state.
 */
export async function signEncounterNote(
  params: SignEncounterNoteParams,
): Promise<EncounterNote> {
  const { tenantSlug, encounterId, noteId, signal } = params
  return apiFetch<EncounterNote>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/encounters/${encodeURIComponent(encounterId)}/notes/${encodeURIComponent(noteId)}/sign`,
    {
      method: 'POST',
      tenantSlug,
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}

export interface AmendEncounterNoteParams {
  tenantSlug: string
  encounterId: string
  noteId: string
  body: string
  signal?: AbortSignal
}

/**
 * Amend a SIGNED note (Phase 4D.6). Creates a NEW DRAFT note
 * referencing the original via `amends_id`. The original is
 * never mutated — V20's immutability trigger guarantees that
 * even at the DB layer.
 *
 * URL pattern matches the 4D.5 sign action: `.../{noteId}/amend`.
 * The new amendment goes through the existing 4D.5 sign endpoint
 * to be promoted to SIGNED. Per the 4D.6 chunk B.5 carve-out,
 * amendments may be signed regardless of the parent encounter's
 * status — that's the point of post-encounter correction.
 *
 * - 201 → returns the new DRAFT amendment with `amendsId`
 *   populated. Caller invalidates the encounter-notes list so
 *   the new row threads under the original.
 * - 403 → caller lacks `NOTE_WRITE`.
 * - 404 → unknown noteId / cross-tenant / cross-encounter
 *   (no existence leak).
 * - 409 `resource.conflict` with one of:
 *   - `details.reason: cannot_amend_unsigned_note` — the
 *     target note is still DRAFT.
 *   - `details.reason: cannot_amend_an_amendment` — single-
 *     level chain rule.
 *   - `details.reason: amendment_integrity_violation` — V23
 *     trigger fired (rare; race or post-handler bypass).
 */
export async function amendEncounterNote(
  params: AmendEncounterNoteParams,
): Promise<EncounterNote> {
  const { tenantSlug, encounterId, noteId, body, signal } = params
  return apiFetch<EncounterNote>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/encounters/${encodeURIComponent(encounterId)}/notes/${encodeURIComponent(noteId)}/amend`,
    {
      method: 'POST',
      body: { body },
      tenantSlug,
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}
