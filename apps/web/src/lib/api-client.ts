import { getToken } from '@/lib/auth'

/**
 * Thin fetch wrapper. Attaches the bearer token from the shared
 * auth module, sets JSON headers, and unwraps the canonical
 * `{ data, requestId }` envelope the API returns.
 *
 * Throws ApiError on non-2xx. Never logs request/response bodies
 * (PHI discipline — callers that need to log must redact first).
 */

export interface ApiEnvelope<T> {
  data: T
  requestId: string
}

export class ApiError extends Error {
  readonly status: number
  readonly requestId: string | undefined
  readonly body: unknown

  constructor(
    message: string,
    status: number,
    requestId: string | undefined,
    body: unknown,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.requestId = requestId
    this.body = body
  }
}

export interface ApiFetchOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  tenantSlug?: string
  signal?: AbortSignal
  /**
   * Extra request headers (e.g., `If-Match` for PATCH). Merged
   * AFTER the framework-managed headers (Accept,
   * Content-Type, Authorization, X-Medcore-Tenant), so a caller
   * can override any of them — but the only documented use is
   * adding new headers, not overriding the auth/tenant pair.
   */
  headers?: Record<string, string>
}

export async function apiFetch<T>(
  path: string,
  options: ApiFetchOptions = {},
): Promise<T> {
  const { method = 'GET', body, tenantSlug, signal, headers: extraHeaders } = options

  const headers = new Headers()
  headers.set('Accept', 'application/json')
  if (body !== undefined) {
    headers.set('Content-Type', 'application/json')
  }
  const token = getToken()
  if (token !== null) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  if (tenantSlug !== undefined) {
    headers.set('X-Medcore-Tenant', tenantSlug)
  }
  if (extraHeaders !== undefined) {
    for (const [k, v] of Object.entries(extraHeaders)) {
      headers.set(k, v)
    }
  }

  const init: RequestInit = {
    method,
    headers,
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    ...(signal ? { signal } : {}),
  }

  const response = await fetch(path, init)

  const requestId = response.headers.get('X-Request-Id') ?? undefined

  let parsed: unknown = null
  const contentType = response.headers.get('Content-Type') ?? ''
  if (contentType.includes('application/json')) {
    try {
      parsed = await response.json()
    } catch {
      parsed = null
    }
  }

  if (!response.ok) {
    throw new ApiError(
      `Request failed with status ${response.status}`,
      response.status,
      requestId,
      parsed,
    )
  }

  const envelope = parsed as ApiEnvelope<T> | null
  if (envelope && 'data' in envelope) {
    return envelope.data
  }
  return parsed as T
}
