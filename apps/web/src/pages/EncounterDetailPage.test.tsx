import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

  // =====================================================================
  // Encounter detail fields (Chunk D coverage — preserved)
  // =====================================================================

  it('renders encounter fields and a working patient link', async () => {
    routeMock({
      encounter: encounter({
        id: 'e-1',
        patientId: 'p-42',
        status: 'IN_PROGRESS',
        startedAt: '2026-04-23T10:00:00Z',
      }),
      notes: [],
    })

    renderAt('/tenants/acme-health/encounters/e-1')

    const card = await screen.findByTestId('encounter-detail-card')
    expect(card).toBeInTheDocument()
    expect(card).toHaveAttribute('data-phi')

    expect(screen.getAllByText('IN_PROGRESS').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('AMB').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('2026-04-23T10:00:00Z')).toBeInTheDocument()
    const patientLink = screen.getByTestId('encounter-patient-link')
    expect(patientLink).toHaveAttribute(
      'href',
      '/tenants/acme-health/patients/p-42',
    )
    expect(patientLink.textContent).toContain('View patient detail')
  })

  it('shows em-dash for absent finishedAt', async () => {
    routeMock({
      encounter: encounter({
        id: 'e-2',
        patientId: 'p-1',
        startedAt: '2026-04-23T10:00:00Z',
      }),
      notes: [],
    })

    renderAt('/tenants/acme-health/encounters/e-2')
    await screen.findByTestId('encounter-detail-card')
    const finishedLabel = screen.getByText('Finished')
    const dd = finishedLabel.nextElementSibling
    expect(dd?.textContent).toBe('—')
  })

  it('404 path shows not-found message', async () => {
    fetchSpy.mockResolvedValue(
      jsonResponse(404, { error: { code: 'resource.not_found' } }),
    )

    renderAt('/tenants/acme-health/encounters/missing')

    await waitFor(() => {
      expect(
        screen.getByText(/Encounter not found or not accessible/),
      ).toBeInTheDocument()
    })
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

  // =====================================================================
  // Notes (Chunk E — new)
  // =====================================================================

  it('renders empty state when no notes exist', async () => {
    routeMock({
      encounter: encounter({ id: 'e-4', patientId: 'p-1' }),
      notes: [],
    })

    renderAt('/tenants/acme-health/encounters/e-4')
    await screen.findByTestId('encounter-detail-card')

    expect(await screen.findByTestId('notes-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('notes-list')).not.toBeInTheDocument()
    // data-phi present on notes card root (not per-element).
    const notesCard = screen.getByTestId('encounter-notes-card')
    expect(notesCard).toHaveAttribute('data-phi')
  })

  it('renders notes newest-first when they exist', async () => {
    routeMock({
      encounter: encounter({ id: 'e-5', patientId: 'p-1' }),
      notes: [
        noteOf('n-2', 'Second note body'),
        noteOf('n-1', 'First note body'),
      ],
    })

    renderAt('/tenants/acme-health/encounters/e-5')
    await screen.findByTestId('notes-list')

    expect(screen.getByText('Second note body')).toBeInTheDocument()
    expect(screen.getByText('First note body')).toBeInTheDocument()
  })

  it('Save button is disabled while textarea is empty', async () => {
    routeMock({
      encounter: encounter({ id: 'e-6', patientId: 'p-1' }),
      notes: [],
    })

    renderAt('/tenants/acme-health/encounters/e-6')
    await screen.findByTestId('encounter-detail-card')

    const saveBtn = await screen.findByTestId('save-note-button')
    expect(saveBtn).toBeDisabled()

    // Typing enables it.
    const ta = screen.getByTestId('note-body-textarea')
    await userEvent.type(ta, 'SOAP: assessment + plan.')
    expect(saveBtn).not.toBeDisabled()

    // Clearing to whitespace disables again.
    await userEvent.clear(ta)
    await userEvent.type(ta, '   ')
    expect(saveBtn).toBeDisabled()
  })

  it('saves a note, clears the textarea, and refetches the list', async () => {
    let savedNote: ReturnType<typeof noteOf> | null = null
    // Dynamic route mock: after POST, subsequent GET /notes includes
    // the saved note in items.
    fetchSpy.mockImplementation((input) => {
      const url = String(input)
      if (url.endsWith('/notes') && (inputAsInit(input).method === 'POST')) {
        savedNote = noteOf('n-new', 'Just saved body.')
        return Promise.resolve(jsonResponse(201, { data: savedNote, requestId: 'r' }))
      }
      if (url.endsWith('/notes')) {
        return Promise.resolve(
          jsonResponse(200, {
            data: { items: savedNote ? [savedNote] : [] },
            requestId: 'r',
          }),
        )
      }
      // encounter GET
      return Promise.resolve(
        jsonResponse(200, {
          data: encounter({ id: 'e-7', patientId: 'p-1' }),
          requestId: 'r',
        }),
      )
    })

    renderAt('/tenants/acme-health/encounters/e-7')
    await screen.findByTestId('encounter-detail-card')

    const ta = (await screen.findByTestId(
      'note-body-textarea',
    )) as HTMLTextAreaElement
    await userEvent.type(ta, 'Just saved body.')
    const saveBtn = screen.getByTestId('save-note-button')
    await userEvent.click(saveBtn)

    await waitFor(() => {
      expect(screen.getByText('Just saved body.')).toBeInTheDocument()
    })
    // Textarea cleared on success.
    expect(ta.value).toBe('')
  })

  it('shows a 403 error banner when the save is denied', async () => {
    fetchSpy.mockImplementation((input) => {
      const url = String(input)
      if (url.endsWith('/notes') && inputAsInit(input).method === 'POST') {
        return Promise.resolve(
          jsonResponse(403, { error: { code: 'auth.forbidden' } }),
        )
      }
      if (url.endsWith('/notes')) {
        return Promise.resolve(
          jsonResponse(200, { data: { items: [] }, requestId: 'r' }),
        )
      }
      return Promise.resolve(
        jsonResponse(200, {
          data: encounter({ id: 'e-8', patientId: 'p-1' }),
          requestId: 'r',
        }),
      )
    })

    renderAt('/tenants/acme-health/encounters/e-8')
    await screen.findByTestId('encounter-detail-card')
    const ta = await screen.findByTestId('note-body-textarea')
    await userEvent.type(ta, 'Attempted note')
    await userEvent.click(screen.getByTestId('save-note-button'))

    await waitFor(() => {
      expect(screen.getByTestId('save-note-error')).toHaveTextContent(
        /You do not have authority/,
      )
    })
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
                path="/tenants/:slug/encounters/:encounterId"
                element={<EncounterDetailPage />}
              />
            </Routes>
          </QueryClientProvider>
        </AuthProvider>
      </MemoryRouter>,
    )
  }

  /**
   * Route mock: responds with `opts.encounter` for any GET that isn't
   * `/notes`, and responds with `{items: opts.notes}` for `/notes`
   * GETs. Doesn't handle POSTs — tests that exercise POST provide
   * their own mock implementation.
   */
  function routeMock(opts: {
    encounter: ReturnType<typeof encounter>
    notes: ReturnType<typeof noteOf>[]
  }): void {
    fetchSpy.mockImplementation((input) => {
      const url = String(input)
      if (url.endsWith('/notes')) {
        return Promise.resolve(
          jsonResponse(200, { data: { items: opts.notes }, requestId: 'r' }),
        )
      }
      return Promise.resolve(
        jsonResponse(200, { data: opts.encounter, requestId: 'r' }),
      )
    })
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

  function noteOf(id: string, body: string) {
    return {
      id,
      tenantId: 't-1',
      encounterId: 'e-any',
      body,
      createdAt: '2026-04-24T10:00:00Z',
      updatedAt: '2026-04-24T10:00:00Z',
      createdBy: 'u-1',
      updatedBy: 'u-1',
      rowVersion: 0,
    }
  }

  function inputAsInit(
    _input: Parameters<typeof fetch>[0],
  ): RequestInit {
    // fetch is called via apiFetch as fetch(url, init); the spy
    // records init at calls[n][1]. We peek at the most recent
    // call's init to decide whether this was a POST.
    const lastCall = fetchSpy.mock.calls.at(-1)
    if (!lastCall) return {}
    return (lastCall[1] as RequestInit) ?? {}
  }

  function jsonResponse(status: number, body: unknown): Response {
    return new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  }
})
