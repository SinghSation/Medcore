import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { getPatient, listPatients } from '@/lib/patients'
import { ApiError } from '@/lib/api-client'
import { clearToken, setToken } from '@/lib/auth'

describe('listPatients', () => {
  const fetchSpy = vi.fn<typeof fetch>()

  beforeEach(() => {
    clearToken()
    setToken('test-token')
    vi.stubGlobal('fetch', fetchSpy)
  })

  afterEach(() => {
    fetchSpy.mockReset()
    vi.unstubAllGlobals()
    clearToken()
  })

  it('calls the tenant-scoped list endpoint with bearer + tenant header', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, {
        data: emptyPage(),
        requestId: 'req-1',
      }),
    )

    await listPatients({ tenantSlug: 'acme-health' })

    const [url, init] = fetchSpy.mock.calls.at(-1)!
    expect(url).toBe('/api/v1/tenants/acme-health/patients')
    const headers = new Headers((init as RequestInit).headers)
    expect(headers.get('Authorization')).toBe('Bearer test-token')
    expect(headers.get('X-Medcore-Tenant')).toBe('acme-health')
  })

  it('serialises limit + offset into the query string', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, { data: emptyPage(), requestId: 'r' }),
    )

    await listPatients({ tenantSlug: 'acme-health', limit: 10, offset: 30 })

    const url = fetchSpy.mock.calls.at(-1)![0] as string
    expect(url).toBe('/api/v1/tenants/acme-health/patients?limit=10&offset=30')
  })

  it('unwraps the {data, requestId} envelope and returns the page', async () => {
    const page = {
      items: [
        {
          id: 'p1',
          mrn: '000001',
          nameGiven: 'Ada',
          nameFamily: 'Lovelace',
          birthDate: '1960-05-15',
          administrativeSex: 'female',
          createdAt: '2026-01-01T00:00:00Z',
        },
      ],
      totalCount: 1,
      limit: 20,
      offset: 0,
      hasMore: false,
    }
    fetchSpy.mockResolvedValue(
      jsonResponse(200, { data: page, requestId: 'r' }),
    )

    const result = await listPatients({ tenantSlug: 'acme-health' })

    expect(result.items).toHaveLength(1)
    expect(result.items[0]!.nameFamily).toBe('Lovelace')
    expect(result.totalCount).toBe(1)
    expect(result.hasMore).toBe(false)
  })

  it('URL-encodes the tenant slug', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, { data: emptyPage(), requestId: 'r' }),
    )

    await listPatients({ tenantSlug: 'acme health' })

    const url = fetchSpy.mock.calls.at(-1)![0] as string
    expect(url).toBe('/api/v1/tenants/acme%20health/patients')
  })

  function emptyPage() {
    return {
      items: [],
      totalCount: 0,
      limit: 20,
      offset: 0,
      hasMore: false,
    }
  }

  function jsonResponse(status: number, body: unknown): Response {
    return new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  }
})

describe('getPatient', () => {
  const fetchSpy = vi.fn<typeof fetch>()

  beforeEach(() => {
    clearToken()
    setToken('detail-token')
    vi.stubGlobal('fetch', fetchSpy)
  })

  afterEach(() => {
    fetchSpy.mockReset()
    vi.unstubAllGlobals()
    clearToken()
  })

  it('calls the tenant-scoped detail endpoint with bearer + tenant header', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, {
        data: detailOf('pid-1', 'Ada', 'Lovelace'),
        requestId: 'r',
      }),
    )

    await getPatient({ tenantSlug: 'acme-health', patientId: 'pid-1' })

    const [url, init] = fetchSpy.mock.calls.at(-1)!
    expect(url).toBe('/api/v1/tenants/acme-health/patients/pid-1')
    const headers = new Headers((init as RequestInit).headers)
    expect(headers.get('Authorization')).toBe('Bearer detail-token')
    expect(headers.get('X-Medcore-Tenant')).toBe('acme-health')
  })

  it('propagates a typed ApiError(404) when the API returns not-found', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(404, { error: { code: 'resource.not_found' } }),
    )

    await expect(
      getPatient({ tenantSlug: 'acme-health', patientId: 'missing' }),
    ).rejects.toMatchObject({ name: 'ApiError', status: 404 })
    await expect(
      getPatient({ tenantSlug: 'acme-health', patientId: 'missing' }),
    ).rejects.toBeInstanceOf(ApiError)
  })

  function detailOf(id: string, given: string, family: string) {
    return {
      id,
      tenantId: 't-1',
      mrn: '000001',
      mrnSource: 'GENERATED',
      nameGiven: given,
      nameFamily: family,
      birthDate: '1960-05-15',
      administrativeSex: 'female',
      status: 'ACTIVE',
      rowVersion: 0,
      createdAt: '2026-04-01T00:00:00Z',
      updatedAt: '2026-04-01T00:00:00Z',
      createdBy: 'u-1',
      updatedBy: 'u-1',
    }
  }

  function jsonResponse(status: number, body: unknown): Response {
    return new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  }
})
