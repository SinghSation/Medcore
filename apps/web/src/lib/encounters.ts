import { apiFetch } from '@/lib/api-client'

export type EncounterStatus =
  | 'PLANNED'
  | 'IN_PROGRESS'
  | 'FINISHED'
  | 'CANCELLED'

export type EncounterClass = 'AMB'

/**
 * Wire shape of `EncounterResponse` from
 * `POST /patients/{id}/encounters` + `GET /encounters/{id}`.
 *
 * Optional timestamps (`startedAt`, `finishedAt`) may be absent
 * when the API omits them (`@JsonInclude(NON_NULL)`).
 */
export interface Encounter {
  id: string
  tenantId: string
  patientId: string
  status: EncounterStatus
  encounterClass: EncounterClass
  startedAt?: string
  finishedAt?: string
  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
  rowVersion: number
}

export interface StartEncounterParams {
  tenantSlug: string
  patientId: string
  encounterClass: EncounterClass
  signal?: AbortSignal
}

export async function startEncounter(
  params: StartEncounterParams,
): Promise<Encounter> {
  const { tenantSlug, patientId, encounterClass, signal } = params
  return apiFetch<Encounter>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/patients/${encodeURIComponent(patientId)}/encounters`,
    {
      method: 'POST',
      body: { encounterClass },
      tenantSlug,
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}

export interface GetEncounterParams {
  tenantSlug: string
  encounterId: string
  signal?: AbortSignal
}

export async function getEncounter(
  params: GetEncounterParams,
): Promise<Encounter> {
  const { tenantSlug, encounterId, signal } = params
  return apiFetch<Encounter>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/encounters/${encodeURIComponent(encounterId)}`,
    {
      tenantSlug,
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}
