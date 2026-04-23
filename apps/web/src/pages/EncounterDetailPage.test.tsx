import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AuthProvider } from '@/providers/AuthProvider'
import { EncounterDetailPage } from '@/pages/EncounterDetailPage'
import { clearToken, setToken } from '@/lib/auth'

describe('<EncounterDetailPage />', () => {
  const fetchSpy = vi.fn<typeof fetch>()

  beforeEach(() => {
    clearToken()
    setToken('enc-token')
    vi.stubGlobal('fetch', fetchSpy)
  })

  afterEach(() => {
    fetchSpy.mockReset()
    vi.unstubAllGlobals()
    clearToken()
  })

  it('renders encounter fields and a working patient link', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, {
        data: encounter({
          id: 'e-1',
          patientId: 'p-42',
          status: 'IN_PROGRESS',
          startedAt: '2026-04-23T10:00:00Z',
        }),
        requestId: 'r',
      }),
    )

    renderAt('/tenants/acme-health/encounters/e-1')

    const card = await screen.findByTestId('encounter-detail-card')
    expect(card).toBeInTheDocument()
    expect(card).toHaveAttribute('data-phi')
    // The minimum fields per the approved constraint.
    expect(screen.getAllByText('IN_PROGRESS').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('AMB').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('2026-04-23T10:00:00Z')).toBeInTheDocument()
    // Patient link navigates to detail, not raw id.
    const patientLink = screen.getByTestId('encounter-patient-link')
    expect(patientLink).toHaveAttribute(
      'href',
      '/tenants/acme-health/patients/p-42',
    )
    expect(patientLink.textContent).toContain('View patient detail')
  })

  it('shows em-dash for absent finishedAt', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, {
        data: encounter({
          id: 'e-2',
          patientId: 'p-1',
          status: 'IN_PROGRESS',
          startedAt: '2026-04-23T10:00:00Z',
        }),
        requestId: 'r',
      }),
    )

    renderAt('/tenants/acme-health/encounters/e-2')
    await screen.findByTestId('encounter-detail-card')
    // The "Finished" dt label has a matching dd rendering "—".
    const finishedLabel = screen.getByText('Finished')
    const dd = finishedLabel.nextElementSibling
    expect(dd?.textContent).toBe('—')
  })

  it('404 path shows not-found message with back link', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(404, { error: { code: 'resource.not_found' } }),
    )

    renderAt('/tenants/acme-health/encounters/missing')

    await waitFor(() => {
      expect(
        screen.getByText(/Encounter not found or not accessible/),
      ).toBeInTheDocument()
    })
    expect(
      screen.queryByRole('button', { name: /Retry/ }),
    ).not.toBeInTheDocument()
  })

  it('500 path shows destructive error with Retry', async () => {
    fetchSpy.mockResolvedValue(jsonResponse(500, {}))

    renderAt('/tenants/acme-health/encounters/e-3')

    await waitFor(
      () => {
        expect(screen.getByText(/Could not load encounter/)).toBeInTheDocument()
      },
      { timeout: 5000 },
    )
    expect(screen.getByRole('button', { name: /Retry/ })).toBeInTheDocument()
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
                path="/tenants/:slug/encounters/:encounterId"
                element={<EncounterDetailPage />}
              />
            </Routes>
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
  }

  function encounter(
    overrides: Partial<{
      id: string
      patientId: string
      status: 'PLANNED' | 'IN_PROGRESS' | 'FINISHED' | 'CANCELLED'
      startedAt: string
    }>,
  ) {
    return {
      id: overrides.id ?? 'e-default',
      tenantId: 't-1',
      patientId: overrides.patientId ?? 'p-default',
      status: overrides.status ?? 'IN_PROGRESS',
      encounterClass: 'AMB',
      startedAt: overrides.startedAt,
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
