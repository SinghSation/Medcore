import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { apiFetch, ApiError } from '@/lib/api-client'
import { clearToken, setToken } from '@/lib/auth'

describe('apiFetch', () => {
  const fetchSpy = vi.fn<typeof fetch>()

  beforeEach(() => {
    clearToken()
    vi.stubGlobal('fetch', fetchSpy)
  })

  afterEach(() => {
    fetchSpy.mockReset()
    vi.unstubAllGlobals()
    clearToken()
  })

  it('attaches the bearer token when set', async () => {
    setToken('t0ken')
    fetchSpy.mockResolvedValue(jsonResponse(200, { foo: 'bar' }))

    await apiFetch('/api/v1/me')

    const headers = lastHeaders()
    expect(headers.get('Authorization')).toBe('Bearer t0ken')
  })

  it('omits Authorization when no token is set', async () => {
    fetchSpy.mockResolvedValue(jsonResponse(200, { foo: 'bar' }))

    await apiFetch('/api/v1/me')

    const headers = lastHeaders()
    expect(headers.has('Authorization')).toBe(false)
  })

  it('unwraps {data, requestId} envelopes', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, { data: { id: 'abc' }, requestId: 'req-1' }),
    )

    const result = await apiFetch<{ id: string }>('/api/v1/thing')

    expect(result).toEqual({ id: 'abc' })
  })

  it('returns raw body when no envelope is present', async () => {
    fetchSpy.mockResolvedValue(jsonResponse(200, { items: [1, 2, 3] }))

    const result = await apiFetch<{ items: number[] }>('/api/v1/tenants')

    expect(result).toEqual({ items: [1, 2, 3] })
  })

  it('throws ApiError on non-2xx with status and parsed body', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(401, { error: { code: 'auth.unauthenticated' } }),
    )

    await expect(apiFetch('/api/v1/me')).rejects.toMatchObject({
      name: 'ApiError',
      status: 401,
    })
  })

  it('exposes ApiError as a typed instance', async () => {
    fetchSpy.mockResolvedValue(jsonResponse(500, { error: { code: 'x' } }))

    await expect(apiFetch('/api/v1/x')).rejects.toBeInstanceOf(ApiError)
  })

  it('adds X-Medcore-Tenant when tenantSlug is supplied', async () => {
    setToken('t')
    fetchSpy.mockResolvedValue(jsonResponse(200, { ok: true }))

    await apiFetch('/api/v1/tenants/acme/patients', { tenantSlug: 'acme' })

    expect(lastHeaders().get('X-Medcore-Tenant')).toBe('acme')
  })

  function lastHeaders(): Headers {
    const call = fetchSpy.mock.calls.at(-1)
    if (!call) throw new Error('fetch was never called')
    const init = call[1] as RequestInit
    return new Headers(init.headers)
  }

  function jsonResponse(status: number, body: unknown): Response {
    return new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  }
})
