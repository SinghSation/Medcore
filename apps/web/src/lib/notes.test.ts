import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  amendEncounterNote,
  createEncounterNote,
  listEncounterNotes,
} from '@/lib/notes'
import { ApiError } from '@/lib/api-client'
import { clearToken, setToken } from '@/lib/auth'
import { pagedDataMock } from '@/lib/pagination.test-utils'

describe('encounter notes API client', () => {
  const fetchSpy = vi.fn<typeof fetch>()

  beforeEach(() => {
    clearToken()
    setToken('notes-token')
    vi.stubGlobal('fetch', fetchSpy)
  })

  afterEach(() => {
    fetchSpy.mockReset()
    vi.unstubAllGlobals()
    clearToken()
  })

  it('createEncounterNote POSTs JSON with bearer + tenant headers', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(201, { data: noteOf('n-1'), requestId: 'r' }),
    )

    await createEncounterNote({
      tenantSlug: 'acme-health',
      encounterId: 'e-1',
      body: 'Chief complaint…',
    })

    const [url, init] = fetchSpy.mock.calls.at(-1)!
    expect(url).toBe(
      '/api/v1/tenants/acme-health/encounters/e-1/notes',
    )
    const req = init as RequestInit
    expect(req.method).toBe('POST')
    expect(JSON.parse(req.body as string)).toEqual({ body: 'Chief complaint…' })
    const headers = new Headers(req.headers)
    expect(headers.get('Authorization')).toBe('Bearer notes-token')
    expect(headers.get('X-Medcore-Tenant')).toBe('acme-health')
    expect(headers.get('Content-Type')).toBe('application/json')
  })

  it('listEncounterNotes GETs the list with bearer + tenant headers', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, {
        data: pagedDataMock([noteOf('n-1'), noteOf('n-2')]),
        requestId: 'r',
      }),
    )

    const result = await listEncounterNotes({
      tenantSlug: 'acme-health',
      encounterId: 'e-1',
    })

    const [url, init] = fetchSpy.mock.calls.at(-1)!
    expect(url).toBe('/api/v1/tenants/acme-health/encounters/e-1/notes')
    const headers = new Headers((init as RequestInit).headers)
    expect(headers.get('Authorization')).toBe('Bearer notes-token')
    expect(result.items).toHaveLength(2)
  })

  it('propagates ApiError(403) from createEncounterNote on forbidden', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(403, { error: { code: 'auth.forbidden' } }),
    )

    await expect(
      createEncounterNote({
        tenantSlug: 'acme-health',
        encounterId: 'e-1',
        body: 'denied',
      }),
    ).rejects.toBeInstanceOf(ApiError)
    await expect(
      createEncounterNote({
        tenantSlug: 'acme-health',
        encounterId: 'e-1',
        body: 'denied',
      }),
    ).rejects.toMatchObject({ status: 403 })
  })

  it('propagates ApiError(404) from listEncounterNotes on unknown encounter', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(404, { error: { code: 'resource.not_found' } }),
    )

    await expect(
      listEncounterNotes({
        tenantSlug: 'acme-health',
        encounterId: 'missing',
      }),
    ).rejects.toMatchObject({ status: 404 })
  })

  // --- Phase 4D.6: amend ---

  it('amendEncounterNote POSTs to /amend with body + bearer + tenant headers', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(201, {
        data: { ...noteOf('amend-1'), amendsId: 'orig-1' },
        requestId: 'r',
      }),
    )

    await amendEncounterNote({
      tenantSlug: 'acme-health',
      encounterId: 'e-1',
      noteId: 'orig-1',
      body: 'Correction: dosage was wrong.',
    })

    const [url, init] = fetchSpy.mock.calls.at(-1)!
    expect(url).toBe('/api/v1/tenants/acme-health/encounters/e-1/notes/orig-1/amend')
    const req = init as RequestInit
    expect(req.method).toBe('POST')
    expect(JSON.parse(req.body as string)).toEqual({
      body: 'Correction: dosage was wrong.',
    })
    const headers = new Headers(req.headers)
    expect(headers.get('Authorization')).toBe('Bearer notes-token')
    expect(headers.get('X-Medcore-Tenant')).toBe('acme-health')
    expect(headers.get('Content-Type')).toBe('application/json')
  })

  it('propagates ApiError(409) with details.reason on conflict surfaces', async () => {
    // The platform contract: every 409 carries `code` + `message`
    // + `details.reason`. The lib propagates the raw body so
    // callers (e.g. EncounterDetailPage) can dispatch on it. The
    // 409 fixture mirrors the exact shape `GlobalExceptionHandler.
    // onWriteConflict` emits — keeping the mock honest to the API
    // contract even though the assertion below only narrows on
    // `details.reason`.
    fetchSpy.mockResolvedValue(
      jsonResponse(409, {
        code: 'resource.conflict',
        message: 'The request conflicts with the current state of the resource.',
        details: { reason: 'cannot_amend_unsigned_note' },
      }),
    )

    const error = await amendEncounterNote({
      tenantSlug: 'acme-health',
      encounterId: 'e-1',
      noteId: 'still-draft',
      body: 'attempt',
    }).catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409 })
    expect((error as ApiError).body).toMatchObject({
      details: { reason: 'cannot_amend_unsigned_note' },
    })
  })

  it('propagates ApiError(403) when caller lacks NOTE_WRITE', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(403, { error: { code: 'auth.forbidden' } }),
    )

    await expect(
      amendEncounterNote({
        tenantSlug: 'acme-health',
        encounterId: 'e-1',
        noteId: 'orig-1',
        body: 'denied',
      }),
    ).rejects.toMatchObject({ status: 403 })
  })

  function noteOf(id: string) {
    return {
      id,
      tenantId: 't-1',
      encounterId: 'e-1',
      body: 'A clinical note.',
      createdAt: '2026-04-24T10:00:00Z',
      updatedAt: '2026-04-24T10:00:00Z',
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
