import { apiFetch } from '@/lib/api-client'

export type EncounterStatus =
  | 'PLANNED'
  | 'IN_PROGRESS'
  | 'FINISHED'
  | 'CANCELLED'

export type EncounterClass = 'AMB'

export type CancelReason =
  | 'NO_SHOW'
  | 'PATIENT_DECLINED'
  | 'SCHEDULING_ERROR'
  | 'OTHER'

/**
 * Wire shape of `EncounterResponse` from
 * `POST /patients/{id}/encounters`, `GET /encounters/{id}`,
 * `POST .../finish`, and `POST .../cancel`.
 *
 * Optional fields are absent when the API omits them
 * (`@JsonInclude(NON_NULL)`):
 *   - `startedAt`, `finishedAt` — encounter-lifecycle timestamps.
 *   - `cancelledAt`, `cancelReason` — present ⇔ status=CANCELLED.
 */
export interface Encounter {
  id: string
  tenantId: string
  patientId: string
  status: EncounterStatus
  encounterClass: EncounterClass
  startedAt?: string
  finishedAt?: string
  cancelledAt?: string
  cancelReason?: CancelReason
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

/**
 * Wire shape of `EncounterListResponse` from
 * `GET /patients/{id}/encounters` (Phase 4C.3). Un-paginated;
 * envelope mirrors `EncounterNoteList` — `items` only.
 */
export interface EncounterList {
  items: Encounter[]
}

export interface ListPatientEncountersParams {
  tenantSlug: string
  patientId: string
  signal?: AbortSignal
}

export async function listPatientEncounters(
  params: ListPatientEncountersParams,
): Promise<EncounterList> {
  const { tenantSlug, patientId, signal } = params
  return apiFetch<EncounterList>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/patients/${encodeURIComponent(patientId)}/encounters`,
    {
      tenantSlug,
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}

export interface FinishEncounterParams {
  tenantSlug: string
  encounterId: string
  signal?: AbortSignal
}

/**
 * Finish an encounter (Phase 4C.5). POST to `/finish` —
 * HL7 FHIR action-style URL for state transitions. No request
 * body.
 *
 * - 200 → returns the finished `Encounter` with `status:
 *   'FINISHED'` and `finishedAt` populated.
 * - 403 → caller lacks `ENCOUNTER_WRITE`.
 * - 409 `resource.conflict` with
 *   `details.reason: encounter_already_closed` → double-finish
 *   or finishing a CANCELLED encounter.
 * - 409 `resource.conflict` with
 *   `details.reason: encounter_has_no_signed_notes` →
 *   precondition unmet; the encounter has no signed notes.
 */
export async function finishEncounter(
  params: FinishEncounterParams,
): Promise<Encounter> {
  const { tenantSlug, encounterId, signal } = params
  return apiFetch<Encounter>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/encounters/${encodeURIComponent(encounterId)}/finish`,
    {
      method: 'POST',
      tenantSlug,
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}

export interface CancelEncounterParams {
  tenantSlug: string
  encounterId: string
  cancelReason: CancelReason
  signal?: AbortSignal
}

/**
 * Cancel an encounter (Phase 4C.5). POST to `/cancel` with a
 * closed-enum reason.
 *
 * - 200 → returns the cancelled `Encounter` with `status:
 *   'CANCELLED'`, `cancelledAt`, `cancelReason` populated.
 * - 403 → caller lacks `ENCOUNTER_WRITE`.
 * - 409 `resource.conflict` with
 *   `details.reason: encounter_already_closed` → cancelling
 *   an already-closed encounter.
 * - 422 → missing or unknown `cancelReason`.
 */
export async function cancelEncounter(
  params: CancelEncounterParams,
): Promise<Encounter> {
  const { tenantSlug, encounterId, cancelReason, signal } = params
  return apiFetch<Encounter>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/encounters/${encodeURIComponent(encounterId)}/cancel`,
    {
      method: 'POST',
      body: { cancelReason },
      tenantSlug,
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}
