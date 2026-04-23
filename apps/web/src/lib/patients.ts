import { apiFetch } from '@/lib/api-client'

/**
 * Summary-view wire shape returned by
 * `GET /api/v1/tenants/{slug}/patients`. Deliberately narrower
 * than the detail view — see backend `PatientListItemResponse`
 * KDoc. Frontend must never log these objects.
 */
export interface PatientListItem {
  id: string
  mrn: string
  nameGiven: string
  nameFamily: string
  birthDate: string
  administrativeSex: 'male' | 'female' | 'other' | 'unknown'
  createdAt: string
}

export interface PatientListPage {
  items: PatientListItem[]
  totalCount: number
  limit: number
  offset: number
  hasMore: boolean
}

export interface ListPatientsParams {
  tenantSlug: string
  limit?: number
  offset?: number
  signal?: AbortSignal
}

export async function listPatients(
  params: ListPatientsParams,
): Promise<PatientListPage> {
  const { tenantSlug, limit, offset, signal } = params
  const query = new URLSearchParams()
  if (limit !== undefined) query.set('limit', String(limit))
  if (offset !== undefined) query.set('offset', String(offset))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return apiFetch<PatientListPage>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/patients${suffix}`,
    {
      tenantSlug,
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}

/**
 * Full detail wire shape returned by
 * `GET /api/v1/tenants/{slug}/patients/{patientId}` (Phase 4A.4).
 *
 * Superset of [PatientListItem]. Carries the full demographic
 * surface plus audit-metadata fields. Rendered on the detail
 * page. Frontend MUST NOT log these objects or persist them to
 * browser storage (Rule 01, Rule 04).
 */
export type PatientStatus = 'ACTIVE' | 'MERGED' | 'DELETED'
export type MrnSource = 'GENERATED' | 'IMPORTED'

export interface PatientDetail {
  id: string
  tenantId: string
  mrn: string
  mrnSource: MrnSource

  nameGiven: string
  nameFamily: string
  nameMiddle?: string
  nameSuffix?: string
  namePrefix?: string
  preferredName?: string

  birthDate: string
  administrativeSex: 'male' | 'female' | 'other' | 'unknown'
  sexAssignedAtBirth?: string
  genderIdentityCode?: string
  preferredLanguage?: string

  status: PatientStatus
  rowVersion: number

  createdAt: string
  updatedAt: string
  createdBy: string
  updatedBy: string
}

export interface GetPatientParams {
  tenantSlug: string
  patientId: string
  signal?: AbortSignal
}

export async function getPatient(
  params: GetPatientParams,
): Promise<PatientDetail> {
  const { tenantSlug, patientId, signal } = params
  return apiFetch<PatientDetail>(
    `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/patients/${encodeURIComponent(patientId)}`,
    {
      tenantSlug,
      ...(signal !== undefined ? { signal } : {}),
    },
  )
}
