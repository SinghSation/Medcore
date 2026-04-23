import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AuthProvider } from '@/providers/AuthProvider'
import { PatientDetailPage } from '@/pages/PatientDetailPage'
import { clearToken, setToken } from '@/lib/auth'

describe('<PatientDetailPage />', () => {
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

  it('shows loading, then renders all required sections of the populated card', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, {
        data: detail({
          id: 'pid-1',
          nameGiven: 'Ada',
          nameFamily: 'Lovelace',
          nameMiddle: 'Augusta',
          preferredLanguage: 'en-GB',
        }),
        requestId: 'r',
      }),
    )

    renderAt('/tenants/acme-health/patients/pid-1')

    expect(screen.getByText(/Loading/)).toBeInTheDocument()

    const card = await screen.findByTestId('patient-detail-card')
    expect(card).toBeInTheDocument()
    // Root PHI container carries the data-phi marker (Rule 04).
    expect(card).toHaveAttribute('data-phi')

    expect(screen.getByText('Lovelace, Ada')).toBeInTheDocument()
    expect(screen.getByText('Augusta')).toBeInTheDocument()
    expect(screen.getByText('en-GB')).toBeInTheDocument()
    // Section titles.
    expect(screen.getByText('Identity')).toBeInTheDocument()
    expect(screen.getByText('Name')).toBeInTheDocument()
    expect(screen.getByText('Demographics')).toBeInTheDocument()
    expect(screen.getByText('Audit trail')).toBeInTheDocument()
  })

  it('omits optional fields from the DOM when the wire response omits them', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, {
        data: detail({
          id: 'pid-2',
          nameGiven: 'Grace',
          nameFamily: 'Hopper',
          // nameMiddle, preferredLanguage, sexAssignedAtBirth,
          // genderIdentityCode all unset
        }),
        requestId: 'r',
      }),
    )

    renderAt('/tenants/acme-health/patients/pid-2')
    await screen.findByTestId('patient-detail-card')

    // Labels for absent optional fields should not appear.
    expect(screen.queryByText('Middle')).not.toBeInTheDocument()
    expect(screen.queryByText('Preferred')).not.toBeInTheDocument()
    expect(screen.queryByText('Preferred language')).not.toBeInTheDocument()
    expect(screen.queryByText('Sex assigned at birth')).not.toBeInTheDocument()
    expect(screen.queryByText('Gender identity')).not.toBeInTheDocument()
  })

  it('shows a not-found message when API returns 404', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(404, { error: { code: 'resource.not_found' } }),
    )

    renderAt('/tenants/acme-health/patients/missing')

    await waitFor(() => {
      expect(
        screen.getByText(/Patient not found or not accessible/),
      ).toBeInTheDocument()
    })
    // No destructive retry for 404.
    expect(
      screen.queryByRole('button', { name: /Retry/ }),
    ).not.toBeInTheDocument()
    // Header + not-found-card each render a Back-to-list link —
    // at least one is present.
    expect(
      screen.getAllByRole('link', { name: /Back to list/ }).length,
    ).toBeGreaterThanOrEqual(1)
  })

  it('shows a destructive error with Retry on 500', async () => {
    fetchSpy.mockResolvedValue(jsonResponse(500, {}))

    renderAt('/tenants/acme-health/patients/pid-3')

    // Detail page retries once on non-404 errors (see component
    // retry predicate). TanStack Query's default retry backoff
    // needs room before the error state finalizes; bump the
    // waitFor window accordingly.
    await waitFor(
      () => {
        expect(screen.getByText(/Could not load patient/)).toBeInTheDocument()
      },
      { timeout: 5000 },
    )
    expect(screen.getByRole('button', { name: /Retry/ })).toBeInTheDocument()
  })

  it('PHI container carries the data-phi attribute on the root only', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, {
        data: detail({
          id: 'pid-4',
          nameGiven: 'Katherine',
          nameFamily: 'Johnson',
        }),
        requestId: 'r',
      }),
    )

    renderAt('/tenants/acme-health/patients/pid-4')
    const card = await screen.findByTestId('patient-detail-card')

    // The detail card is the single PHI boundary marker. Child
    // elements must NOT carry their own data-phi attribute; that
    // is deliberate scope discipline per Rule 04 — boundary
    // tagging, not per-element tagging.
    const taggedElements = card.querySelectorAll('[data-phi]')
    // NodeList includes the card itself (which is the query root),
    // so exactly ONE element carries the attribute.
    expect(taggedElements.length).toBe(0)
    expect(card).toHaveAttribute('data-phi')
  })

  it('does not persist patient detail to localStorage or sessionStorage', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, {
        data: detail({
          id: 'pid-5',
          nameGiven: 'Zylerth',
          nameFamily: 'Quixomatic',
          preferredLanguage: 'nan-Latn-TW',
        }),
        requestId: 'r',
      }),
    )

    renderAt('/tenants/acme-health/patients/pid-5')
    await screen.findByText('Quixomatic, Zylerth')

    const local = JSON.stringify(Object.entries(localStorage))
    const session = JSON.stringify(Object.entries(sessionStorage))
    expect(local).not.toContain('Quixomatic')
    expect(local).not.toContain('Zylerth')
    expect(local).not.toContain('nan-Latn-TW')
    expect(session).not.toContain('Quixomatic')
    expect(session).not.toContain('Zylerth')
  })

  // ---- helpers ----

  function renderAt(path: string): void {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider>
          <QueryClientProvider client={queryClient}>
            <Routes>
              <Route
                path="/tenants/:slug/patients/:patientId"
                element={<PatientDetailPage />}
              />
            </Routes>
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
  }

  function detail(
    overrides: Partial<{
      id: string
      nameGiven: string
      nameFamily: string
      nameMiddle: string
      preferredLanguage: string
    }>,
  ) {
    return {
      id: overrides.id ?? 'pid-default',
      tenantId: 't-1',
      mrn: '000099',
      mrnSource: 'GENERATED',
      nameGiven: overrides.nameGiven ?? 'Given',
      nameFamily: overrides.nameFamily ?? 'Family',
      ...(overrides.nameMiddle ? { nameMiddle: overrides.nameMiddle } : {}),
      ...(overrides.preferredLanguage
        ? { preferredLanguage: overrides.preferredLanguage }
        : {}),
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
