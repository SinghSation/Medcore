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
