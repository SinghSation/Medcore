import { render, screen, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AuthProvider } from '@/providers/AuthProvider'
import { PatientListPage } from '@/pages/PatientListPage'
import { clearToken, setToken } from '@/lib/auth'

describe('<PatientListPage />', () => {
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

  it('renders a loading state, then the patient table', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, {
        data: {
          items: [
            itemOf('p1', '000001', 'Ada', 'Lovelace', '1960-05-15', 'female'),
            itemOf('p2', '000002', 'Grace', 'Hopper', '1906-12-09', 'female'),
          ],
          totalCount: 2,
          limit: 20,
          offset: 0,
          hasMore: false,
        },
        requestId: 'r',
      }),
    )

    renderAt('/tenants/acme-health/patients')

    // Loading indicator renders before the query resolves.
    expect(screen.getByText(/Loading/)).toBeInTheDocument()

    const table = await screen.findByTestId('patient-list-table')
    expect(table).toBeInTheDocument()
    expect(screen.getByText('Lovelace')).toBeInTheDocument()
    expect(screen.getByText('Hopper')).toBeInTheDocument()
    expect(screen.getByText(/Showing 1–2 of 2/)).toBeInTheDocument()
  })

  it('renders an empty-state when the tenant has no patients', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, {
        data: {
          items: [],
          totalCount: 0,
          limit: 20,
          offset: 0,
          hasMore: false,
        },
        requestId: 'r',
      }),
    )

    renderAt('/tenants/acme-health/patients')

    await waitFor(() => {
      expect(
        screen.getByText(/No patients yet in this tenant/),
      ).toBeInTheDocument()
    })
    expect(screen.getByText(/Showing 0–0 of 0/)).toBeInTheDocument()
  })

  it('renders an error + Retry button on API failure', async () => {
    fetchSpy.mockResolvedValue(jsonResponse(500, {}))

    renderAt('/tenants/acme-health/patients')

    await waitFor(() => {
      expect(screen.getByText(/Could not load patients/)).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: /Retry/ })).toBeInTheDocument()
  })

  it('paginates forward when Next is clicked', async () => {
    const firstPage = {
      items: [
        itemOf('p1', '000001', 'Ada', 'Lovelace', '1960-05-15', 'female'),
      ],
      totalCount: 2,
      limit: 20,
      offset: 0,
      hasMore: true,
    }
    const secondPage = {
      items: [
        itemOf('p2', '000002', 'Grace', 'Hopper', '1906-12-09', 'female'),
      ],
      totalCount: 2,
      limit: 20,
      offset: 20,
      hasMore: false,
    }
    fetchSpy
      .mockResolvedValueOnce(
        jsonResponse(200, { data: firstPage, requestId: 'r' }),
      )
      .mockResolvedValueOnce(
        jsonResponse(200, { data: secondPage, requestId: 'r' }),
      )

    renderAt('/tenants/acme-health/patients')
    await screen.findByTestId('patient-list-table')

    const next = screen.getByRole('button', { name: /Next/ })
    await act(async () => {
      await userEvent.click(next)
    })

    await waitFor(() => {
      expect(screen.getByText('Hopper')).toBeInTheDocument()
    })

    const urls = fetchSpy.mock.calls.map((c) => c[0] as string)
    expect(urls[1]).toBe(
      '/api/v1/tenants/acme-health/patients?limit=20&offset=20',
    )
  })

  it('does not persist patient data to localStorage or sessionStorage', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(200, {
        data: {
          items: [
            itemOf('p1', '000001', 'Zylerth', 'Quixomatic', '1960-05-15', 'female'),
          ],
          totalCount: 1,
          limit: 20,
          offset: 0,
          hasMore: false,
        },
        requestId: 'r',
      }),
    )

    renderAt('/tenants/acme-health/patients')
    await screen.findByText('Quixomatic')

    // PHI discipline: PHI never lands in browser storage.
    const local = JSON.stringify(Object.entries(localStorage))
    const session = JSON.stringify(Object.entries(sessionStorage))
    expect(local).not.toContain('Quixomatic')
    expect(local).not.toContain('Zylerth')
    expect(session).not.toContain('Quixomatic')
    expect(session).not.toContain('Zylerth')
  })

  // ---- helpers ----

  function renderAt(path: string): void {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    })
    render(
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider>
          <QueryClientProvider client={queryClient}>
            <Routes>
              <Route
                path="/tenants/:slug/patients"
                element={<PatientListPage />}
              />
            </Routes>
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
  }

  function itemOf(
    id: string,
    mrn: string,
    given: string,
    family: string,
    birthDate: string,
    sex: 'male' | 'female' | 'other' | 'unknown',
  ) {
    return {
      id,
      mrn,
      nameGiven: given,
      nameFamily: family,
      birthDate,
      administrativeSex: sex,
      createdAt: '2026-01-01T00:00:00Z',
    }
  }

  function jsonResponse(status: number, body: unknown): Response {
    return new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  }
})
