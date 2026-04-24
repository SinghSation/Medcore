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
 *   - `amendsId` — reserved for the future amendment workflow;
 *     always absent in 4D.5.
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

export interface EncounterNoteList {
  items: EncounterNote[]
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
