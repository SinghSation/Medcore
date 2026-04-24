import { apiFetch } from '@/lib/api-client'

/**
 * Wire shape of `EncounterNoteResponse` from
 * `POST /encounters/{id}/notes` and from each item in
 * `GET /encounters/{id}/notes`. Body is PHI — never log,
 * never persist client-side.
 */
export interface EncounterNote {
  id: string
  tenantId: string
  encounterId: string
  body: string
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
