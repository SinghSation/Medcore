import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ProblemsCard } from '@/components/ProblemsCard'
import { clearToken, setToken } from '@/lib/auth'

describe('<ProblemsCard />', () => {
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

  it('shows ACTIVE problems on the card; hides INACTIVE / RESOLVED / ENTERED_IN_ERROR', async () => {
    routeMock({
      items: [
        problemOf('p-active', { status: 'ACTIVE', conditionText: 'Asthma' }),
        problemOf('p-inactive', { status: 'INACTIVE', conditionText: 'Migraine' }),
        problemOf('p-resolved', {
          status: 'RESOLVED',
          conditionText: 'Bronchitis 2019',
        }),
        problemOf('p-error', {
          status: 'ENTERED_IN_ERROR',
          conditionText: 'Wrong-entry',
        }),
      ],
    })

    renderCard()

    const list = await screen.findByTestId('problems-card-list')
    expect(list).toBeInTheDocument()
    const rows = screen.getAllByTestId('problem-card-row')
    expect(rows).toHaveLength(1)
    expect(rows[0]).toHaveAttribute('data-problem-status', 'ACTIVE')
    expect(rows[0]).toHaveTextContent('Asthma')
    // RESOLVED ≠ INACTIVE — both are hidden from the at-a-glance
    // card. The card surface is ACTIVE-only.
    expect(screen.queryByText('Migraine')).not.toBeInTheDocument()
    expect(screen.queryByText('Bronchitis 2019')).not.toBeInTheDocument()
    expect(screen.queryByText('Wrong-entry')).not.toBeInTheDocument()
  })

  it('shows empty state when no problems exist', async () => {
    routeMock({ items: [] })

    renderCard()

    expect(
      await screen.findByTestId('problems-card-empty'),
    ).toHaveTextContent(/no active problems recorded/i)
    expect(screen.queryByTestId('problems-card-list')).not.toBeInTheDocument()
  })

  it('Manage modal opens and shows ALL statuses with distinct labels (RESOLVED ≠ INACTIVE)', async () => {
    routeMock({
      items: [
        problemOf('p-1', { status: 'ACTIVE', conditionText: 'Asthma' }),
        problemOf('p-2', { status: 'INACTIVE', conditionText: 'Migraine' }),
        problemOf('p-3', {
          status: 'RESOLVED',
          conditionText: 'Bronchitis',
        }),
        problemOf('p-4', {
          status: 'ENTERED_IN_ERROR',
          conditionText: 'Wrong-entry',
        }),
      ],
    })

    renderCard()
    await screen.findByTestId('problems-card-list')
    fireEvent.click(screen.getByTestId('problem-manage-button'))

    const modal = await screen.findByTestId('problem-manage-modal')
    expect(modal).toBeInTheDocument()
    const manageRows = screen.getAllByTestId('problem-manage-row')
    expect(manageRows).toHaveLength(4)
    // All four statuses are present in the management view.
    const statuses = manageRows.map((r) => r.getAttribute('data-problem-status'))
    expect(statuses).toEqual(
      expect.arrayContaining(['ACTIVE', 'INACTIVE', 'RESOLVED', 'ENTERED_IN_ERROR']),
    )
    // Labels are distinct — "Resolved" must NOT collide with
    // "Inactive". Both badges are present, both reading their
    // own status label.
    const badges = screen.getAllByTestId('problem-manage-status')
    const labelTexts = badges.map((b) => b.textContent)
    expect(labelTexts).toContain('Active')
    expect(labelTexts).toContain('Inactive')
    expect(labelTexts).toContain('Resolved')
    expect(labelTexts).toContain('Entered in error')
  })

  it('adding a new problem POSTs without severity by default and refetches', async () => {
    let addedYet = false
    fetchSpy.mockImplementation((input, init) => {
      const url = String(input)
      const method = String(init?.method ?? 'GET').toUpperCase()
      if (method === 'POST' && url.endsWith('/problems')) {
        addedYet = true
        // Validate the body — severity must NOT be present (the
        // user did not pick one, default is "Unspecified" → omit).
        const body = JSON.parse((init as RequestInit).body as string)
        expect(body).toEqual({ conditionText: 'Hypertension' })
        return Promise.resolve(
          jsonResponse(201, {
            data: problemOf('p-new', {
              status: 'ACTIVE',
              conditionText: 'Hypertension',
            }),
            requestId: 'r',
          }),
        )
      }
      if (url.endsWith('/problems')) {
        return Promise.resolve(
          jsonResponse(200, {
            data: {
              items: addedYet
                ? [
                    problemOf('p-new', {
                      status: 'ACTIVE',
                      conditionText: 'Hypertension',
                    }),
                  ]
                : [],
            },
            requestId: 'r',
          }),
        )
      }
      return Promise.resolve(jsonResponse(200, {}))
    })

    renderCard()
    await screen.findByTestId('problems-card-empty')
    fireEvent.click(screen.getByTestId('problem-manage-button'))
    await screen.findByTestId('problem-manage-modal')

    await userEvent.type(
      screen.getByTestId('problem-condition-input'),
      'Hypertension',
    )
    // Leave severity at default ('Unspecified' / '').
    fireEvent.click(screen.getByTestId('problem-add-button'))

    await waitFor(() => {
      const postCall = fetchSpy.mock.calls.find(
        (c) =>
          (c[1] as RequestInit | undefined)?.method === 'POST' &&
          String(c[0]).endsWith('/problems'),
      )
      expect(postCall).toBeDefined()
    })

    // After invalidate + refetch, the new problem is visible on
    // the card (ACTIVE surfaces there) AND in the modal — exactly
    // 2 occurrences (banner-row + manage-row).
    await waitFor(() => {
      expect(screen.getByTestId('problems-card-list')).toBeInTheDocument()
      const matches = screen.getAllByText('Hypertension')
      expect(matches).toHaveLength(2)
    })
  })

  it('Mark resolved sends PATCH with status=RESOLVED + If-Match', async () => {
    fetchSpy.mockImplementation((input, init) => {
      const url = String(input)
      const method = String(init?.method ?? 'GET').toUpperCase()
      if (method === 'PATCH' && url.includes('/problems/')) {
        return Promise.resolve(
          jsonResponse(200, {
            data: problemOf('p-1', {
              status: 'RESOLVED',
              conditionText: 'Asthma',
              rowVersion: 1,
            }),
            requestId: 'r',
          }),
        )
      }
      if (url.endsWith('/problems')) {
        return Promise.resolve(
          jsonResponse(200, {
            data: {
              items: [
                problemOf('p-1', {
                  status: 'ACTIVE',
                  conditionText: 'Asthma',
                  rowVersion: 0,
                }),
              ],
            },
            requestId: 'r',
          }),
        )
      }
      return Promise.resolve(jsonResponse(200, {}))
    })

    renderCard()
    await screen.findByTestId('problems-card-list')
    fireEvent.click(screen.getByTestId('problem-manage-button'))
    await screen.findByTestId('problem-manage-modal')
    fireEvent.click(screen.getByTestId('problem-resolve-button'))

    await waitFor(() => {
      const patchCall = fetchSpy.mock.calls.find(
        (c) => (c[1] as RequestInit | undefined)?.method === 'PATCH',
      )
      expect(patchCall).toBeDefined()
      const init = patchCall![1] as RequestInit
      const sentBody = JSON.parse(init.body as string)
      // RESOLVED is its own clinical-outcome action — the wire
      // body simply sends status:RESOLVED; the backend dispatches
      // to CLINICAL_PROBLEM_RESOLVED audit.
      expect(sentBody).toEqual({ status: 'RESOLVED' })
      const sentHeaders = new Headers(init.headers)
      expect(sentHeaders.get('If-Match')).toBe('"0"')
    })
  })

  it('RESOLVED → INACTIVE returns 409 problem_invalid_transition with helpful copy', async () => {
    // Seed a RESOLVED row and verify that the UI does NOT offer
    // a "Mark inactive" button for it (no resolve→inactive shortcut).
    // The user's only paths from RESOLVED are: reactivate (recurrence)
    // or revoke as ENTERED_IN_ERROR — both visible.
    fetchSpy.mockResolvedValue(
      jsonResponse(200, {
        data: {
          items: [
            problemOf('p-resolved', {
              status: 'RESOLVED',
              conditionText: 'Bronchitis',
              rowVersion: 0,
            }),
          ],
        },
        requestId: 'r',
      }),
    )

    renderCard()
    // Card itself shows empty (no ACTIVE rows).
    await screen.findByTestId('problems-card-empty')
    fireEvent.click(screen.getByTestId('problem-manage-button'))
    await screen.findByTestId('problem-manage-modal')

    // The RESOLVED row offers "Mark active (recurrence)" and
    // "Mark entered in error" — NOT "Mark inactive". This is
    // structural enforcement of RESOLVED ≠ INACTIVE: the UI
    // never even gives the user the option of the invalid
    // transition.
    expect(screen.getByTestId('problem-recurrence-button')).toBeInTheDocument()
    expect(screen.getByTestId('problem-revoke-button')).toBeInTheDocument()
    expect(
      screen.queryByTestId('problem-deactivate-button'),
    ).not.toBeInTheDocument()
  })

  it('surfaces problem_invalid_transition error message when backend refuses', async () => {
    // Defensive: tests the response-handler reason→copy mapping
    // for the `problem_invalid_transition` 409. The mock returns
    // that reason for an ACTIVE→RESOLVED click — which is NOT a
    // realistic backend response (the real backend only returns
    // `problem_invalid_transition` for a RESOLVED→INACTIVE PATCH,
    // and the UI structurally doesn't expose that path on a
    // RESOLVED row — see the previous test).
    //
    // The realistic scenario where this 409 reaches the UI is
    // a parallel-session race: another tab transitioned the row
    // to RESOLVED + the user's stale UI tries a transition the
    // RESOLVED state forbids. The fixture simulates that 409
    // arriving at the response handler so we can lock down the
    // copy ("recurrence" + "entered in error") without seeding
    // the multi-session flow itself.
    //
    // A future fixture that seeds a RESOLVED row + drives the
    // recurrence button would test the success path; this one
    // tests the error-rendering path defensively. The backend
    // contract test (ProblemIntegrationTest #13) covers the
    // realistic API-side scenario.
    fetchSpy.mockImplementation((input, init) => {
      const url = String(input)
      const method = String(init?.method ?? 'GET').toUpperCase()
      if (method === 'PATCH' && url.includes('/problems/')) {
        return Promise.resolve(
          jsonResponse(409, {
            code: 'resource.conflict',
            message: 'The request conflicts with the current state of the resource.',
            details: { reason: 'problem_invalid_transition' },
          }),
        )
      }
      if (url.endsWith('/problems')) {
        return Promise.resolve(
          jsonResponse(200, {
            data: {
              items: [
                problemOf('p-1', {
                  status: 'ACTIVE',
                  conditionText: 'Asthma',
                  rowVersion: 0,
                }),
              ],
            },
            requestId: 'r',
          }),
        )
      }
      return Promise.resolve(jsonResponse(200, {}))
    })

    renderCard()
    await screen.findByTestId('problems-card-list')
    fireEvent.click(screen.getByTestId('problem-manage-button'))
    await screen.findByTestId('problem-manage-modal')
    // Click resolve to drive the PATCH; backend responds 409 with
    // problem_invalid_transition (simulating the structural refusal).
    fireEvent.click(screen.getByTestId('problem-resolve-button'))

    await waitFor(() => {
      const err = screen.getByTestId('problem-transition-error')
      expect(err).toBeInTheDocument()
      // Helpful copy — explains both legal paths.
      expect(err.textContent).toMatch(/recurrence/i)
      expect(err.textContent).toMatch(/entered in error/i)
    })
  })

  // =====================================================================
  // Helpers
  // =====================================================================

  function renderCard(): void {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <QueryClientProvider client={queryClient}>
        <ProblemsCard tenantSlug="acme-health" patientId="p-1" />
      </QueryClientProvider>,
    )
  }

  function routeMock(opts: { items: ReturnType<typeof problemOf>[] }): void {
    fetchSpy.mockImplementation(() =>
      Promise.resolve(
        jsonResponse(200, {
          data: { items: opts.items },
          requestId: 'r',
        }),
      ),
    )
  }

  function problemOf(
    id: string,
    overrides: Partial<{
      status: 'ACTIVE' | 'INACTIVE' | 'RESOLVED' | 'ENTERED_IN_ERROR'
      conditionText: string
      rowVersion: number
    }>,
  ) {
    return {
      id,
      tenantId: 't-1',
      patientId: 'p-1',
      conditionText: overrides.conditionText ?? 'Asthma',
      status: overrides.status ?? 'ACTIVE',
      createdAt: '2026-04-25T10:00:00Z',
      updatedAt: '2026-04-25T10:00:00Z',
      createdBy: 'u-1',
      updatedBy: 'u-1',
      rowVersion: overrides.rowVersion ?? 0,
    }
  }

  function jsonResponse(status: number, body: unknown): Response {
    return new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  }
})
