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

  // =====================================================================
  // Patient detail fields (preserved)
  // =====================================================================

  it('shows loading, then renders all required sections of the populated card', async () => {
    routeMock({
      patient: detail({
        id: 'pid-1',
        nameGiven: 'Ada',
        nameFamily: 'Lovelace',
        nameMiddle: 'Augusta',
        preferredLanguage: 'en-GB',
      }),
      encounters: [],
    })

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
    routeMock({
      patient: detail({
        id: 'pid-2',
        nameGiven: 'Grace',
        nameFamily: 'Hopper',
      }),
      encounters: [],
    })

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
    expect(
      screen.queryByRole('button', { name: /Retry/ }),
    ).not.toBeInTheDocument()
    expect(
      screen.getAllByRole('link', { name: /Back to list/ }).length,
    ).toBeGreaterThanOrEqual(1)
  })

  it('shows a destructive error with Retry on 500', async () => {
    fetchSpy.mockResolvedValue(jsonResponse(500, {}))

    renderAt('/tenants/acme-health/patients/pid-3')

    // TanStack Query default retry backoff — give the error state
    // time to finalize.
    await waitFor(
      () => {
        expect(screen.getByText(/Could not load patient/)).toBeInTheDocument()
      },
      { timeout: 5000 },
    )
    expect(screen.getByRole('button', { name: /Retry/ })).toBeInTheDocument()
  })

  it('PHI container carries the data-phi attribute on the root only', async () => {
    routeMock({
      patient: detail({
        id: 'pid-4',
        nameGiven: 'Katherine',
        nameFamily: 'Johnson',
      }),
      encounters: [],
    })

    renderAt('/tenants/acme-health/patients/pid-4')
    const card = await screen.findByTestId('patient-detail-card')

    // The detail card is a PHI boundary marker. Child elements inside
    // the detail card must NOT carry their own data-phi attribute;
    // that is deliberate scope discipline per Rule 04 — boundary
    // tagging, not per-element tagging. The sibling encounters card
    // is its own boundary and has its own data-phi attribute.
    const taggedElements = card.querySelectorAll('[data-phi]')
    expect(taggedElements.length).toBe(0)
    expect(card).toHaveAttribute('data-phi')
  })

  it('does not persist patient detail to localStorage or sessionStorage', async () => {
    routeMock({
      patient: detail({
        id: 'pid-5',
        nameGiven: 'Zylerth',
        nameFamily: 'Quixomatic',
        preferredLanguage: 'nan-Latn-TW',
      }),
      encounters: [],
    })

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

  // =====================================================================
  // Encounters card (Phase 4C.3 — new)
  // =====================================================================

  it('renders empty state when patient has no encounters', async () => {
    routeMock({
      patient: detail({ id: 'pid-6', nameGiven: 'A', nameFamily: 'B' }),
      encounters: [],
    })

    renderAt('/tenants/acme-health/patients/pid-6')
    await screen.findByTestId('patient-detail-card')

    expect(await screen.findByTestId('patient-encounters-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('patient-encounters-list')).not.toBeInTheDocument()
    // data-phi tagged at the encounters-card root.
    const encountersCard = screen.getByTestId('patient-encounters-card')
    expect(encountersCard).toHaveAttribute('data-phi')
  })

  it('renders encounters newest-first with IN_PROGRESS highlight', async () => {
    routeMock({
      patient: detail({ id: 'pid-7', nameGiven: 'A', nameFamily: 'B' }),
      encounters: [
        encounterOf('e-2', 'IN_PROGRESS', '2026-04-24T10:00:00Z'),
        encounterOf('e-1', 'FINISHED', '2026-04-23T10:00:00Z', '2026-04-23T10:30:00Z'),
      ],
    })

    renderAt('/tenants/acme-health/patients/pid-7')
    await screen.findByTestId('patient-encounters-list')

    const rows = screen.getAllByTestId('patient-encounter-row')
    expect(rows).toHaveLength(2)
    // Newest first — IN_PROGRESS comes first per server order.
    expect(rows[0]).toHaveAttribute('data-encounter-status', 'IN_PROGRESS')
    expect(rows[1]).toHaveAttribute('data-encounter-status', 'FINISHED')
  })

  it('links each encounter row to the encounter detail route', async () => {
    routeMock({
      patient: detail({ id: 'pid-8', nameGiven: 'A', nameFamily: 'B' }),
      encounters: [
        encounterOf('enc-42', 'IN_PROGRESS', '2026-04-24T10:00:00Z'),
      ],
    })

    renderAt('/tenants/acme-health/patients/pid-8')
    const link = await screen.findByTestId('patient-encounter-link')
    expect(link).toHaveAttribute(
      'href',
      '/tenants/acme-health/encounters/enc-42',
    )
  })

  // =====================================================================
  // Helpers
  // =====================================================================

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

  /**
   * URL-aware route mock: `/encounters` GETs return the encounter
   * list; everything else returns the patient detail.
   */
  function routeMock(opts: {
    patient: ReturnType<typeof detail>
    encounters: ReturnType<typeof encounterOf>[]
  }): void {
    fetchSpy.mockImplementation((input) => {
      const url = String(input)
      if (url.endsWith('/encounters')) {
        return Promise.resolve(
          jsonResponse(200, {
            data: { items: opts.encounters },
            requestId: 'r',
          }),
        )
      }
      return Promise.resolve(
        jsonResponse(200, { data: opts.patient, requestId: 'r' }),
      )
    })
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

  function encounterOf(
    id: string,
    status: 'PLANNED' | 'IN_PROGRESS' | 'FINISHED' | 'CANCELLED',
    startedAt: string,
    finishedAt?: string,
  ) {
    return {
      id,
      tenantId: 't-1',
      patientId: 'pid-any',
      status,
      encounterClass: 'AMB' as const,
      startedAt,
      ...(finishedAt ? { finishedAt } : {}),
      createdAt: startedAt,
      updatedAt: startedAt,
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
