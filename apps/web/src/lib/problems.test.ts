import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/lib/api-client'
import { clearToken, setToken } from '@/lib/auth'
import { pagedDataMock } from '@/lib/pagination.test-utils'
import {
  addProblem,
  listPatientProblems,
  updateProblem,
} from '@/lib/problems'

describe('problems API client', () => {
  const fetchSpy = vi.fn<typeof fetch>()

  beforeEach(() => {
    clearToken()
    setToken('problem-token')
    vi.stubGlobal('fetch', fetchSpy)
  })

  afterEach(() => {
    fetchSpy.mockReset()
    vi.unstubAllGlobals()
    clearToken()
  })

  it('listPatientProblems GETs the list with bearer + tenant headers', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, {
        data: pagedDataMock([problemOf('p-1')]),
        requestId: 'r',
      }),
    )

    const result = await listPatientProblems({
      tenantSlug: 'acme-health',
      patientId: 'pat-1',
    })

    const [url, init] = fetchSpy.mock.calls.at(-1)!
    expect(url).toBe('/api/v1/tenants/acme-health/patients/pat-1/problems')
    const headers = new Headers((init as RequestInit).headers)
    expect(headers.get('Authorization')).toBe('Bearer problem-token')
    expect(headers.get('X-Medcore-Tenant')).toBe('acme-health')
    expect(result.items).toHaveLength(1)
  })

  it('addProblem POSTs JSON without severity when severity is omitted', async () => {
    // Locked Q3 — severity is nullable; omitting it from the
    // request is the common case for many problems. The lib
    // must NOT serialise `severity: undefined` (would become a
    // wire `null` and trip the validator).
    fetchSpy.mockResolvedValue(
      jsonResponse(201, { data: problemOf('p-2'), requestId: 'r' }),
    )

    await addProblem({
      tenantSlug: 'acme-health',
      patientId: 'pat-1',
      conditionText: 'Type 2 diabetes mellitus',
    })

    const [, init] = fetchSpy.mock.calls.at(-1)!
    const req = init as RequestInit
    expect(req.method).toBe('POST')
    const body = JSON.parse(req.body as string)
    expect(body).toEqual({ conditionText: 'Type 2 diabetes mellitus' })
    // Absent optional fields are NOT serialised.
    expect(body).not.toHaveProperty('severity')
    expect(body).not.toHaveProperty('onsetDate')
    expect(body).not.toHaveProperty('abatementDate')
  })

  it('addProblem POSTs JSON with all optional clinical fields', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(201, { data: problemOf('p-3'), requestId: 'r' }),
    )

    await addProblem({
      tenantSlug: 'acme-health',
      patientId: 'pat-1',
      conditionText: 'Asthma',
      severity: 'MODERATE',
      onsetDate: '2018-04-12',
      abatementDate: '2024-01-15',
    })

    const [, init] = fetchSpy.mock.calls.at(-1)!
    const body = JSON.parse((init as RequestInit).body as string)
    expect(body).toEqual({
      conditionText: 'Asthma',
      severity: 'MODERATE',
      onsetDate: '2018-04-12',
      abatementDate: '2024-01-15',
    })
  })

  it('updateProblem PATCHes with If-Match + three-state body (severity Clear allowed)', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, { data: problemOf('p-4'), requestId: 'r' }),
    )

    await updateProblem({
      tenantSlug: 'acme-health',
      patientId: 'pat-1',
      problemId: 'p-4',
      expectedRowVersion: 2,
      // severity: null → Clear (legal — column is nullable)
      severity: null,
      // onsetDate omitted → Absent
      abatementDate: '2024-01-15',
      status: 'RESOLVED',
    })

    const [url, init] = fetchSpy.mock.calls.at(-1)!
    expect(url).toBe('/api/v1/tenants/acme-health/patients/pat-1/problems/p-4')
    const req = init as RequestInit
    expect(req.method).toBe('PATCH')
    const headers = new Headers(req.headers)
    expect(headers.get('If-Match')).toBe('"2"')
    const body = JSON.parse(req.body as string)
    expect(body).toEqual({
      severity: null,
      abatementDate: '2024-01-15',
      status: 'RESOLVED',
    })
    // onsetDate must NOT appear — Absent semantic.
    expect(body).not.toHaveProperty('onsetDate')
  })

  it('propagates ApiError(409) with details.reason: stale_row', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(409, {
        code: 'resource.conflict',
        message: 'The request conflicts with the current state of the resource.',
        details: { reason: 'stale_row' },
      }),
    )

    const error = await updateProblem({
      tenantSlug: 'acme-health',
      patientId: 'pat-1',
      problemId: 'p-1',
      expectedRowVersion: 0,
      severity: 'MILD',
    }).catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409 })
    expect((error as ApiError).body).toMatchObject({
      details: { reason: 'stale_row' },
    })
  })

  it('propagates ApiError(409) with details.reason: problem_invalid_transition (RESOLVED to INACTIVE)', async () => {
    // The load-bearing RESOLVED ≠ INACTIVE reason. The UI must
    // surface this as a deterministic-reason 409 so users can
    // get a specific error message ("RESOLVED can't go directly
    // to INACTIVE — reactivate first or revoke as ENTERED_IN_ERROR").
    fetchSpy.mockResolvedValue(
      jsonResponse(409, {
        code: 'resource.conflict',
        message: 'The request conflicts with the current state of the resource.',
        details: { reason: 'problem_invalid_transition' },
      }),
    )

    const error = await updateProblem({
      tenantSlug: 'acme-health',
      patientId: 'pat-1',
      problemId: 'p-1',
      expectedRowVersion: 1,
      status: 'INACTIVE',
    }).catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409 })
    expect((error as ApiError).body).toMatchObject({
      details: { reason: 'problem_invalid_transition' },
    })
  })

  it('propagates ApiError(409) with details.reason: problem_terminal', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(409, {
        code: 'resource.conflict',
        message: 'The request conflicts with the current state of the resource.',
        details: { reason: 'problem_terminal' },
      }),
    )

    const error = await updateProblem({
      tenantSlug: 'acme-health',
      patientId: 'pat-1',
      problemId: 'p-1',
      expectedRowVersion: 1,
      status: 'ACTIVE',
    }).catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).body).toMatchObject({
      details: { reason: 'problem_terminal' },
    })
  })

  it('propagates 403 from addProblem when caller lacks PROBLEM_WRITE', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(403, {
        code: 'auth.forbidden',
        message: 'Caller does not have PROBLEM_WRITE on this tenant.',
      }),
    )
    await expect(
      addProblem({
        tenantSlug: 'acme-health',
        patientId: 'pat-1',
        conditionText: 'x',
      }),
    ).rejects.toMatchObject({ status: 403 })
  })

  function problemOf(id: string) {
    return {
      id,
      tenantId: 't-1',
      patientId: 'pat-1',
      conditionText: 'Type 2 diabetes mellitus',
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
