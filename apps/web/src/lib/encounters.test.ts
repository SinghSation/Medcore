import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { getEncounter, startEncounter } from '@/lib/encounters'
import { ApiError } from '@/lib/api-client'
import { clearToken, setToken } from '@/lib/auth'

describe('encounters API client', () => {
  const fetchSpy = vi.fn<typeof fetch>()

  beforeEach(() => {
    clearToken()
    setToken('encounter-token')
    vi.stubGlobal('fetch', fetchSpy)
  })

  afterEach(() => {
    fetchSpy.mockReset()
    vi.unstubAllGlobals()
    clearToken()
  })

  it('startEncounter POSTs to the patient-scoped path with JSON body + bearer + tenant header', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(201, {
        data: encounter('e-1', 'p-1'),
        requestId: 'r',
      }),
    )

    await startEncounter({
      tenantSlug: 'acme-health',
      patientId: 'p-1',
      encounterClass: 'AMB',
    })

    const [url, init] = fetchSpy.mock.calls.at(-1)!
    expect(url).toBe(
      '/api/v1/tenants/acme-health/patients/p-1/encounters',
    )
    const req = init as RequestInit
    expect(req.method).toBe('POST')
    const body = JSON.parse(req.body as string)
    expect(body).toEqual({ encounterClass: 'AMB' })
    const headers = new Headers(req.headers)
    expect(headers.get('Authorization')).toBe('Bearer encounter-token')
    expect(headers.get('X-Medcore-Tenant')).toBe('acme-health')
    expect(headers.get('Content-Type')).toBe('application/json')
  })

  it('getEncounter GETs the tenant-scoped path with bearer + tenant header', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, { data: encounter('e-1', 'p-1'), requestId: 'r' }),
    )

    await getEncounter({
      tenantSlug: 'acme-health',
      encounterId: 'e-1',
    })

    const [url, init] = fetchSpy.mock.calls.at(-1)!
    expect(url).toBe('/api/v1/tenants/acme-health/encounters/e-1')
    const headers = new Headers((init as RequestInit).headers)
    expect(headers.get('Authorization')).toBe('Bearer encounter-token')
    expect(headers.get('X-Medcore-Tenant')).toBe('acme-health')
  })

  it('unwraps the envelope and returns the Encounter body', async () => {
    const body = encounter('e-2', 'p-2')
    fetchSpy.mockResolvedValue(
      jsonResponse(200, { data: body, requestId: 'r' }),
    )

    const result = await getEncounter({
      tenantSlug: 'acme-health',
      encounterId: 'e-2',
    })

    expect(result.id).toBe('e-2')
    expect(result.patientId).toBe('p-2')
    expect(result.status).toBe('IN_PROGRESS')
    expect(result.encounterClass).toBe('AMB')
  })

  it('getEncounter propagates ApiError(404) when the API returns not-found', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(404, { error: { code: 'resource.not_found' } }),
    )

    await expect(
      getEncounter({ tenantSlug: 'acme-health', encounterId: 'missing' }),
    ).rejects.toBeInstanceOf(ApiError)
    await expect(
      getEncounter({ tenantSlug: 'acme-health', encounterId: 'missing' }),
    ).rejects.toMatchObject({ status: 404 })
  })

  function encounter(id: string, patientId: string) {
    return {
      id,
      tenantId: 't-1',
      patientId,
      status: 'IN_PROGRESS',
      encounterClass: 'AMB',
      startedAt: '2026-04-23T10:00:00Z',
      createdAt: '2026-04-23T10:00:00Z',
      updatedAt: '2026-04-23T10:00:00Z',
      createdBy: 'u-1',
      updatedBy: 'u-1',
      rowVersion: 0,
    }
  }

  function jsonResponse(status: number, body: unknown): Response {
    return new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  }
})
