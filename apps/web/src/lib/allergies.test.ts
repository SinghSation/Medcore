import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  addAllergy,
  listPatientAllergies,
  updateAllergy,
} from '@/lib/allergies'
import { ApiError } from '@/lib/api-client'
import { clearToken, setToken } from '@/lib/auth'

describe('allergies API client', () => {
  const fetchSpy = vi.fn<typeof fetch>()

  beforeEach(() => {
    clearToken()
    setToken('allergy-token')
    vi.stubGlobal('fetch', fetchSpy)
  })

  afterEach(() => {
    fetchSpy.mockReset()
    vi.unstubAllGlobals()
    clearToken()
  })

  it('listPatientAllergies GETs the list with bearer + tenant headers', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, {
        data: { items: [allergyOf('a-1')] },
        requestId: 'r',
      }),
    )

    const result = await listPatientAllergies({
      tenantSlug: 'acme-health',
      patientId: 'p-1',
    })

    const [url, init] = fetchSpy.mock.calls.at(-1)!
    expect(url).toBe('/api/v1/tenants/acme-health/patients/p-1/allergies')
    const headers = new Headers((init as RequestInit).headers)
    expect(headers.get('Authorization')).toBe('Bearer allergy-token')
    expect(headers.get('X-Medcore-Tenant')).toBe('acme-health')
    expect(result.items).toHaveLength(1)
  })

  it('addAllergy POSTs JSON with required + optional fields', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(201, { data: allergyOf('a-2'), requestId: 'r' }),
    )

    await addAllergy({
      tenantSlug: 'acme-health',
      patientId: 'p-1',
      substanceText: 'Penicillin',
      severity: 'SEVERE',
      reactionText: 'anaphylaxis',
      onsetDate: '2018-04-12',
    })

    const [url, init] = fetchSpy.mock.calls.at(-1)!
    expect(url).toBe('/api/v1/tenants/acme-health/patients/p-1/allergies')
    const req = init as RequestInit
    expect(req.method).toBe('POST')
    expect(JSON.parse(req.body as string)).toEqual({
      substanceText: 'Penicillin',
      severity: 'SEVERE',
      reactionText: 'anaphylaxis',
      onsetDate: '2018-04-12',
    })
    // Absent optional fields are NOT serialised — matches backend
    // expectations and FHIR cardinality semantics.
    const bodyJson = JSON.parse(req.body as string)
    expect(bodyJson).not.toHaveProperty('recordedInEncounterId')
  })

  it('updateAllergy PATCHes with If-Match header + three-state body', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, { data: allergyOf('a-3'), requestId: 'r' }),
    )

    await updateAllergy({
      tenantSlug: 'acme-health',
      patientId: 'p-1',
      allergyId: 'a-3',
      expectedRowVersion: 2,
      severity: 'MODERATE',
      reactionText: null, // Clear
      // onsetDate omitted — Absent
      status: 'INACTIVE',
    })

    const [url, init] = fetchSpy.mock.calls.at(-1)!
    expect(url).toBe(
      '/api/v1/tenants/acme-health/patients/p-1/allergies/a-3',
    )
    const req = init as RequestInit
    expect(req.method).toBe('PATCH')
    const headers = new Headers(req.headers)
    expect(headers.get('If-Match')).toBe('"2"')
    const body = JSON.parse(req.body as string)
    expect(body).toEqual({
      severity: 'MODERATE',
      reactionText: null,
      status: 'INACTIVE',
    })
    // onsetDate must NOT appear — three-state contract:
    //   undefined → property absent (don't touch column)
    //   null      → property set to null (Clear)
    //   value     → property set to value (Set)
    expect(body).not.toHaveProperty('onsetDate')
  })

  it('propagates ApiError(409) with details.reason for stale_row', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(409, {
        code: 'resource.conflict',
        message: 'The request conflicts with the current state of the resource.',
        details: { reason: 'stale_row' },
      }),
    )

    const error = await updateAllergy({
      tenantSlug: 'acme-health',
      patientId: 'p-1',
      allergyId: 'a-1',
      expectedRowVersion: 0,
      severity: 'MILD',
    }).catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409 })
    expect((error as ApiError).body).toMatchObject({
      details: { reason: 'stale_row' },
    })
  })

  it('propagates ApiError(409) with allergy_terminal reason', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(409, {
        code: 'resource.conflict',
        message: 'The request conflicts with the current state of the resource.',
        details: { reason: 'allergy_terminal' },
      }),
    )

    const error = await updateAllergy({
      tenantSlug: 'acme-health',
      patientId: 'p-1',
      allergyId: 'a-1',
      expectedRowVersion: 1,
      severity: 'MILD',
    }).catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).body).toMatchObject({
      details: { reason: 'allergy_terminal' },
    })
  })

  it('propagates 403 from addAllergy when caller lacks ALLERGY_WRITE', async () => {
    // Platform error envelope is a flat {code, message, details?}
    // (Phase 3G unified envelope) — not a nested {error: {...}}.
    // Aligning the fixture with the real wire shape so the test
    // would surface an envelope regression.
    fetchSpy.mockResolvedValue(
      jsonResponse(403, {
        code: 'auth.forbidden',
        message: 'Caller does not have ALLERGY_WRITE on this tenant.',
      }),
    )
    await expect(
      addAllergy({
        tenantSlug: 'acme-health',
        patientId: 'p-1',
        substanceText: 'x',
        severity: 'MILD',
      }),
    ).rejects.toMatchObject({ status: 403 })
  })

  function allergyOf(id: string) {
    return {
      id,
      tenantId: 't-1',
      patientId: 'p-1',
      substanceText: 'Penicillin',
      severity: 'SEVERE',
      status: 'ACTIVE',
      createdAt: '2026-04-25T10:00:00Z',
      updatedAt: '2026-04-25T10:00:00Z',
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
