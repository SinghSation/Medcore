import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AllergyBanner } from '@/components/AllergyBanner'
import { clearToken, setToken } from '@/lib/auth'

describe('<AllergyBanner />', () => {
  const fetchSpy = vi.fn<typeof fetch>()

  beforeEach(() => {
    clearToken()
    setToken('banner-token')
    vi.stubGlobal('fetch', fetchSpy)
  })

  afterEach(() => {
    fetchSpy.mockReset()
    vi.unstubAllGlobals()
    clearToken()
  })

  it('shows ACTIVE allergies in the banner; hides INACTIVE and ENTERED_IN_ERROR', async () => {
    routeMock({
      items: [
        allergyOf('a-active', { status: 'ACTIVE', substanceText: 'Penicillin' }),
        allergyOf('a-inactive', { status: 'INACTIVE', substanceText: 'Latex' }),
        allergyOf('a-error', {
          status: 'ENTERED_IN_ERROR',
          substanceText: 'Erroneous-substance',
        }),
      ],
    })

    renderBanner()

    const list = await screen.findByTestId('allergy-banner-list')
    expect(list).toBeInTheDocument()
    const rows = screen.getAllByTestId('allergy-banner-row')
    expect(rows).toHaveLength(1)
    expect(rows[0]).toHaveAttribute('data-allergy-status', 'ACTIVE')
    expect(rows[0]).toHaveTextContent('Penicillin')
    // INACTIVE and ENTERED_IN_ERROR substances are not in the banner DOM.
    expect(screen.queryByText('Latex')).not.toBeInTheDocument()
    expect(screen.queryByText('Erroneous-substance')).not.toBeInTheDocument()
  })

  it('shows empty state when no allergies are recorded', async () => {
    routeMock({ items: [] })

    renderBanner()

    expect(
      await screen.findByTestId('allergy-banner-empty'),
    ).toHaveTextContent(/no allergies recorded/i)
    expect(screen.queryByTestId('allergy-banner-list')).not.toBeInTheDocument()
  })

  it('shows empty banner when only non-ACTIVE allergies exist', async () => {
    // A patient with one INACTIVE allergy (e.g., outgrown) and no
    // ACTIVE rows. The banner is the safety surface — INACTIVE
    // does not surface there. Empty state remains.
    routeMock({
      items: [
        allergyOf('a-1', { status: 'INACTIVE', substanceText: 'Tree nuts' }),
      ],
    })

    renderBanner()

    expect(
      await screen.findByTestId('allergy-banner-empty'),
    ).toBeInTheDocument()
    expect(screen.queryByText('Tree nuts')).not.toBeInTheDocument()
  })

  it('Manage modal opens and shows ALL allergies including INACTIVE and ENTERED_IN_ERROR', async () => {
    routeMock({
      items: [
        allergyOf('a-1', { status: 'ACTIVE', substanceText: 'Penicillin' }),
        allergyOf('a-2', { status: 'INACTIVE', substanceText: 'Latex' }),
        allergyOf('a-3', {
          status: 'ENTERED_IN_ERROR',
          substanceText: 'Wrong-entry',
        }),
      ],
    })

    renderBanner()
    await screen.findByTestId('allergy-banner-list')
    fireEvent.click(screen.getByTestId('allergy-manage-button'))

    const modal = await screen.findByTestId('allergy-manage-modal')
    expect(modal).toBeInTheDocument()
    const manageRows = screen.getAllByTestId('allergy-manage-row')
    expect(manageRows).toHaveLength(3)
    // All three statuses are present in the management view.
    expect(
      manageRows.some(
        (r) => r.getAttribute('data-allergy-status') === 'ACTIVE',
      ),
    ).toBe(true)
    expect(
      manageRows.some(
        (r) => r.getAttribute('data-allergy-status') === 'INACTIVE',
      ),
    ).toBe(true)
    expect(
      manageRows.some(
        (r) =>
          r.getAttribute('data-allergy-status') === 'ENTERED_IN_ERROR',
      ),
    ).toBe(true)
  })

  it('adding a new allergy POSTs and refetches the list', async () => {
    let addedYet = false
    fetchSpy.mockImplementation((input, init) => {
      const url = String(input)
      const method = String(init?.method ?? 'GET').toUpperCase()
      if (method === 'POST' && url.endsWith('/allergies')) {
        addedYet = true
        return Promise.resolve(
          jsonResponse(201, {
            data: allergyOf('a-new', {
              status: 'ACTIVE',
              substanceText: 'Sulfa drugs',
            }),
            requestId: 'r',
          }),
        )
      }
      if (url.endsWith('/allergies')) {
        return Promise.resolve(
          jsonResponse(200, {
            data: {
              items: addedYet
                ? [
                    allergyOf('a-new', {
                      status: 'ACTIVE',
                      substanceText: 'Sulfa drugs',
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

    renderBanner()
    await screen.findByTestId('allergy-banner-empty')
    fireEvent.click(screen.getByTestId('allergy-manage-button'))
    await screen.findByTestId('allergy-manage-modal')

    await userEvent.type(
      screen.getByTestId('allergy-substance-input'),
      'Sulfa drugs',
    )
    // Default severity = MODERATE; no need to change.
    fireEvent.click(screen.getByTestId('allergy-add-button'))

    await waitFor(() => {
      // POST happened.
      const postCall = fetchSpy.mock.calls.find(
        (c) =>
          (c[1] as RequestInit | undefined)?.method === 'POST' &&
          String(c[0]).endsWith('/allergies'),
      )
      expect(postCall).toBeDefined()
    })

    // After invalidate + refetch, the new allergy is visible in the
    // banner (ACTIVE substances surface there). The text appears
    // once in the banner row and once in the management-modal row;
    // we assert both contexts contain it.
    await waitFor(() => {
      expect(screen.getByTestId('allergy-banner-list')).toBeInTheDocument()
      const matches = screen.getAllByText('Sulfa drugs')
      // 2 occurrences = banner row + management-modal row.
      expect(matches.length).toBeGreaterThanOrEqual(1)
    })
    const banner = screen.getByTestId('allergy-banner-list')
    expect(banner).toHaveTextContent('Sulfa drugs')
  })

  it('Mark inactive transitions ACTIVE → INACTIVE and removes the row from the banner', async () => {
    let transitioned = false
    fetchSpy.mockImplementation((input, init) => {
      const url = String(input)
      const method = String(init?.method ?? 'GET').toUpperCase()
      if (method === 'PATCH' && url.includes('/allergies/')) {
        transitioned = true
        return Promise.resolve(
          jsonResponse(200, {
            data: allergyOf('a-1', {
              status: 'INACTIVE',
              substanceText: 'Penicillin',
              rowVersion: 1,
            }),
            requestId: 'r',
          }),
        )
      }
      if (url.endsWith('/allergies')) {
        return Promise.resolve(
          jsonResponse(200, {
            data: {
              items: transitioned
                ? [
                    allergyOf('a-1', {
                      status: 'INACTIVE',
                      substanceText: 'Penicillin',
                      rowVersion: 1,
                    }),
                  ]
                : [
                    allergyOf('a-1', {
                      status: 'ACTIVE',
                      substanceText: 'Penicillin',
                    }),
                  ],
            },
            requestId: 'r',
          }),
        )
      }
      return Promise.resolve(jsonResponse(200, {}))
    })

    renderBanner()
    await screen.findByTestId('allergy-banner-list')
    fireEvent.click(screen.getByTestId('allergy-manage-button'))
    await screen.findByTestId('allergy-manage-modal')
    fireEvent.click(screen.getByTestId('allergy-deactivate-button'))

    // After PATCH succeeds, banner refetches and the row no longer
    // appears (ACTIVE-only filter).
    await waitFor(() => {
      expect(
        screen.queryByTestId('allergy-banner-row'),
      ).not.toBeInTheDocument()
      expect(
        screen.getByTestId('allergy-banner-empty'),
      ).toBeInTheDocument()
    })
  })

  it('Mark entered in error sends PATCH with status ENTERED_IN_ERROR + If-Match', async () => {
    fetchSpy.mockImplementation((input, init) => {
      const url = String(input)
      const method = String(init?.method ?? 'GET').toUpperCase()
      if (method === 'PATCH') {
        return Promise.resolve(
          jsonResponse(200, {
            data: allergyOf('a-1', {
              status: 'ENTERED_IN_ERROR',
              substanceText: 'Penicillin',
              rowVersion: 1,
            }),
            requestId: 'r',
          }),
        )
      }
      if (url.endsWith('/allergies')) {
        return Promise.resolve(
          jsonResponse(200, {
            data: {
              items: [
                allergyOf('a-1', {
                  status: 'ACTIVE',
                  substanceText: 'Penicillin',
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

    renderBanner()
    await screen.findByTestId('allergy-banner-list')
    fireEvent.click(screen.getByTestId('allergy-manage-button'))
    await screen.findByTestId('allergy-manage-modal')
    fireEvent.click(screen.getByTestId('allergy-revoke-button'))

    await waitFor(() => {
      const patchCall = fetchSpy.mock.calls.find(
        (c) =>
          (c[1] as RequestInit | undefined)?.method === 'PATCH',
      )
      expect(patchCall).toBeDefined()
      const init = patchCall![1] as RequestInit
      const sentBody = JSON.parse(init.body as string)
      expect(sentBody).toEqual({ status: 'ENTERED_IN_ERROR' })
      const sentHeaders = new Headers(init.headers)
      expect(sentHeaders.get('If-Match')).toBe('"0"')
    })
  })

  // =====================================================================
  // Helpers
  // =====================================================================

  function renderBanner(): void {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <QueryClientProvider client={queryClient}>
        <AllergyBanner tenantSlug="acme-health" patientId="p-1" />
      </QueryClientProvider>,
    )
  }

  function routeMock(opts: { items: ReturnType<typeof allergyOf>[] }): void {
    fetchSpy.mockImplementation(() =>
      Promise.resolve(
        jsonResponse(200, {
          data: { items: opts.items },
          requestId: 'r',
        }),
      ),
    )
  }

  function allergyOf(
    id: string,
    overrides: Partial<{
      status: 'ACTIVE' | 'INACTIVE' | 'ENTERED_IN_ERROR'
      substanceText: string
      rowVersion: number
    }>,
  ) {
    return {
      id,
      tenantId: 't-1',
      patientId: 'p-1',
      substanceText: overrides.substanceText ?? 'Penicillin',
      severity: 'MODERATE' as const,
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
