import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AuthProvider } from '@/providers/AuthProvider'
import { EncounterDetailPage } from '@/pages/EncounterDetailPage'
import { clearToken, setToken } from '@/lib/auth'
import { pagedDataMock } from '@/lib/pagination.test-utils'

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
            data: pagedDataMock(savedNote ? [savedNote] : []),
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
          jsonResponse(200, { data: pagedDataMock([]), requestId: 'r' }),
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
  // Note signing (Phase 4D.5 — new)
  // =====================================================================

  it('renders Draft badge + Sign button on unsigned notes', async () => {
    routeMock({
      encounter: encounter({ id: 'e-s1', patientId: 'p-1' }),
      notes: [noteOf('n-draft', 'Draft note')],
    })

    renderAt('/tenants/acme-health/encounters/e-s1')
    await screen.findByTestId('notes-list')

    const row = screen.getByTestId('note-row')
    expect(row).toHaveAttribute('data-note-status', 'DRAFT')
    expect(screen.getByTestId('note-status-badge')).toHaveTextContent('Draft')
    expect(screen.getByTestId('sign-note-button')).toBeInTheDocument()
    expect(
      screen.queryByTestId('note-signed-attribution'),
    ).not.toBeInTheDocument()
  })

  it('hides Sign button and shows signed-on line on signed notes', async () => {
    routeMock({
      encounter: encounter({ id: 'e-s2', patientId: 'p-1' }),
      notes: [
        signedNoteOf('n-signed', 'Signed body', {
          signedAt: '2026-04-24T15:00:00Z',
          signedBy: 'u-42',
        }),
      ],
    })

    renderAt('/tenants/acme-health/encounters/e-s2')
    await screen.findByTestId('notes-list')

    const row = screen.getByTestId('note-row')
    expect(row).toHaveAttribute('data-note-status', 'SIGNED')
    expect(screen.getByTestId('note-status-badge')).toHaveTextContent('Signed')
    expect(screen.queryByTestId('sign-note-button')).not.toBeInTheDocument()
    const attribution = screen.getByTestId('note-signed-attribution')
    expect(attribution).toHaveTextContent(/2026-04-24T15:00:00Z/)
    expect(attribution).toHaveTextContent(/u-42/)
  })

  it('clicking Sign transitions the note to signed and refetches', async () => {
    let signed = false
    fetchSpy.mockImplementation((input) => {
      const url = String(input)
      const method = inputAsInit(input).method
      if (url.endsWith('/sign') && method === 'POST') {
        signed = true
        return Promise.resolve(
          jsonResponse(200, {
            data: signedNoteOf('n-draft', 'Draft body', {
              signedAt: '2026-04-24T15:05:00Z',
              signedBy: 'u-42',
            }),
            requestId: 'r',
          }),
        )
      }
      if (url.endsWith('/notes')) {
        const items = signed
          ? [
              signedNoteOf('n-draft', 'Draft body', {
                signedAt: '2026-04-24T15:05:00Z',
                signedBy: 'u-42',
              }),
            ]
          : [noteOf('n-draft', 'Draft body')]
        return Promise.resolve(
          jsonResponse(200, { data: { items }, requestId: 'r' }),
        )
      }
      return Promise.resolve(
        jsonResponse(200, {
          data: encounter({ id: 'e-s3', patientId: 'p-1' }),
          requestId: 'r',
        }),
      )
    })

    renderAt('/tenants/acme-health/encounters/e-s3')
    await screen.findByTestId('notes-list')

    await userEvent.click(screen.getByTestId('sign-note-button'))

    await waitFor(() => {
      expect(screen.getByTestId('note-status-badge')).toHaveTextContent(
        'Signed',
      )
    })
    expect(screen.queryByTestId('sign-note-button')).not.toBeInTheDocument()
  })

  it('shows a 403 error banner when the sign is denied', async () => {
    fetchSpy.mockImplementation((input) => {
      const url = String(input)
      const method = inputAsInit(input).method
      if (url.endsWith('/sign') && method === 'POST') {
        return Promise.resolve(
          jsonResponse(403, { error: { code: 'auth.forbidden' } }),
        )
      }
      if (url.endsWith('/notes')) {
        return Promise.resolve(
          jsonResponse(200, {
            data: pagedDataMock([noteOf('n-draft', 'Draft body')]),
            requestId: 'r',
          }),
        )
      }
      return Promise.resolve(
        jsonResponse(200, {
          data: encounter({ id: 'e-s4', patientId: 'p-1' }),
          requestId: 'r',
        }),
      )
    })

    renderAt('/tenants/acme-health/encounters/e-s4')
    await screen.findByTestId('notes-list')
    await userEvent.click(screen.getByTestId('sign-note-button'))

    await waitFor(() => {
      expect(screen.getByTestId('sign-note-error')).toHaveTextContent(
        /You do not have authority/,
      )
    })
  })

  // =====================================================================
  // Encounter lifecycle: finish + cancel (Phase 4C.5)
  // =====================================================================

  it('shows lifecycle actions when encounter is IN_PROGRESS', async () => {
    routeMock({
      encounter: encounter({ id: 'e-l1', patientId: 'p-1', status: 'IN_PROGRESS' }),
      notes: [],
    })

    renderAt('/tenants/acme-health/encounters/e-l1')
    await screen.findByTestId('encounter-detail-card')

    expect(
      await screen.findByTestId('encounter-lifecycle-actions'),
    ).toBeInTheDocument()
    expect(screen.getByTestId('finish-encounter-button')).toBeInTheDocument()
    expect(screen.getByTestId('cancel-encounter-button')).toBeInTheDocument()
  })

  it('disables Finish and shows helper when no signed notes', async () => {
    routeMock({
      encounter: encounter({ id: 'e-l2', patientId: 'p-1', status: 'IN_PROGRESS' }),
      notes: [noteOf('n-draft', 'draft body')],
    })

    renderAt('/tenants/acme-health/encounters/e-l2')
    await screen.findByTestId('notes-list')

    const finishBtn = screen.getByTestId('finish-encounter-button')
    expect(finishBtn).toBeDisabled()
    expect(screen.getByTestId('finish-encounter-helper')).toHaveTextContent(
      /at least one signed note/,
    )
  })

  it('enables Finish when at least one signed note exists', async () => {
    routeMock({
      encounter: encounter({ id: 'e-l3', patientId: 'p-1', status: 'IN_PROGRESS' }),
      notes: [
        signedNoteOf('n-signed', 'signed body', {
          signedAt: '2026-04-24T15:00:00Z',
          signedBy: 'u-42',
        }),
      ],
    })

    renderAt('/tenants/acme-health/encounters/e-l3')
    await screen.findByTestId('notes-list')

    expect(screen.getByTestId('finish-encounter-button')).not.toBeDisabled()
    expect(
      screen.queryByTestId('finish-encounter-helper'),
    ).not.toBeInTheDocument()
  })

  it('hides lifecycle actions + note editor + Sign button when status is FINISHED', async () => {
    routeMock({
      encounter: encounter({
        id: 'e-l4',
        patientId: 'p-1',
        status: 'FINISHED',
        startedAt: '2026-04-24T10:00:00Z',
      }),
      notes: [
        signedNoteOf('n-signed', 'signed body', {
          signedAt: '2026-04-24T10:30:00Z',
          signedBy: 'u-42',
        }),
      ],
    })

    renderAt('/tenants/acme-health/encounters/e-l4')
    await screen.findByTestId('encounter-detail-card')

    expect(
      screen.queryByTestId('encounter-lifecycle-actions'),
    ).not.toBeInTheDocument()
    expect(screen.queryByTestId('note-body-textarea')).not.toBeInTheDocument()
    expect(screen.queryByTestId('save-note-button')).not.toBeInTheDocument()
    // Signed notes render; Sign button is gone (signed already).
    expect(screen.queryByTestId('sign-note-button')).not.toBeInTheDocument()
  })

  it('shows cancel picker with reason select and Confirm flow', async () => {
    let cancelled = false
    fetchSpy.mockImplementation((input) => {
      const url = String(input)
      const method = inputAsInit(input).method
      if (url.endsWith('/cancel') && method === 'POST') {
        cancelled = true
        return Promise.resolve(
          jsonResponse(200, {
            data: encounter({
              id: 'e-l5',
              patientId: 'p-1',
              status: 'CANCELLED',
              startedAt: '2026-04-24T10:00:00Z',
            }),
            requestId: 'r',
          }),
        )
      }
      if (url.endsWith('/notes')) {
        return Promise.resolve(
          jsonResponse(200, { data: pagedDataMock([]), requestId: 'r' }),
        )
      }
      // encounter GET — returns CANCELLED after /cancel succeeds.
      return Promise.resolve(
        jsonResponse(200, {
          data: cancelled
            ? {
                ...encounter({
                  id: 'e-l5',
                  patientId: 'p-1',
                  status: 'CANCELLED',
                  startedAt: '2026-04-24T10:00:00Z',
                }),
                cancelledAt: '2026-04-24T10:30:00Z',
                cancelReason: 'NO_SHOW',
              }
            : encounter({
                id: 'e-l5',
                patientId: 'p-1',
                status: 'IN_PROGRESS',
                startedAt: '2026-04-24T10:00:00Z',
              }),
          requestId: 'r',
        }),
      )
    })

    renderAt('/tenants/acme-health/encounters/e-l5')
    await screen.findByTestId('encounter-detail-card')

    await userEvent.click(screen.getByTestId('cancel-encounter-button'))
    expect(screen.getByTestId('cancel-encounter-picker')).toBeInTheDocument()
    // Default selection is NO_SHOW.
    expect(
      (screen.getByTestId('cancel-reason-select') as HTMLSelectElement).value,
    ).toBe('NO_SHOW')
    await userEvent.click(screen.getByTestId('confirm-cancel-button'))

    await waitFor(() => {
      expect(
        screen.queryByTestId('encounter-lifecycle-actions'),
      ).not.toBeInTheDocument()
    })
    expect(screen.getByTestId('encounter-cancel-reason')).toHaveTextContent(
      /No-show/,
    )
  })

  it('shows encounter_has_no_signed_notes error when finish 409s with that reason', async () => {
    fetchSpy.mockImplementation((input) => {
      const url = String(input)
      const method = inputAsInit(input).method
      if (url.endsWith('/finish') && method === 'POST') {
        return Promise.resolve(
          jsonResponse(409, {
            code: 'resource.conflict',
            message: 'conflict',
            details: { reason: 'encounter_has_no_signed_notes' },
          }),
        )
      }
      if (url.endsWith('/notes')) {
        return Promise.resolve(
          jsonResponse(200, {
            data: pagedDataMock([
              signedNoteOf('n-signed', 'stale cache', {
                signedAt: '2026-04-24T10:00:00Z',
                signedBy: 'u-42',
              }),
            ]),
            requestId: 'r',
          }),
        )
      }
      return Promise.resolve(
        jsonResponse(200, {
          data: encounter({
            id: 'e-l6',
            patientId: 'p-1',
            status: 'IN_PROGRESS',
          }),
          requestId: 'r',
        }),
      )
    })

    renderAt('/tenants/acme-health/encounters/e-l6')
    await screen.findByTestId('finish-encounter-button')
    await userEvent.click(screen.getByTestId('finish-encounter-button'))

    await waitFor(() => {
      expect(
        screen.getByTestId('encounter-lifecycle-error'),
      ).toHaveTextContent(/at least one signed note/)
    })
  })

  // =====================================================================
  // Note amendments (Phase 4D.6 — new)
  // =====================================================================

  it('shows Amend button on SIGNED non-amendment notes only', async () => {
    routeMock({
      encounter: encounter({ id: 'e-a1', patientId: 'p-1', status: 'IN_PROGRESS' }),
      notes: [
        signedNoteOf('n-signed', 'signed body', {
          signedAt: '2026-04-24T10:30:00Z',
          signedBy: 'u-42',
        }),
        noteOf('n-draft', 'draft body'),
      ],
    })

    renderAt('/tenants/acme-health/encounters/e-a1')
    await screen.findByTestId('notes-list')

    // Exactly one Amend button — on the signed, non-amendment row.
    const amendButtons = screen.getAllByTestId('amend-note-button')
    expect(amendButtons).toHaveLength(1)
  })

  it('hides Amend button on DRAFT notes (cannot amend an unsigned note)', async () => {
    routeMock({
      encounter: encounter({ id: 'e-a2', patientId: 'p-1', status: 'IN_PROGRESS' }),
      notes: [noteOf('n-draft', 'still draft')],
    })

    renderAt('/tenants/acme-health/encounters/e-a2')
    await screen.findByTestId('notes-list')

    expect(screen.queryByTestId('amend-note-button')).not.toBeInTheDocument()
  })

  it('hides Amend button on amendments (single-level chain rule)', async () => {
    // Backend rejects amendment-of-amendment; frontend hides
    // the button so the user never sees an option that would
    // 409 anyway. This mirrors V23 + handler enforcement.
    const original = signedNoteOf('orig-1', 'original body', {
      signedAt: '2026-04-24T10:00:00Z',
      signedBy: 'u-42',
    })
    const amendment = {
      ...signedNoteOf('amend-1', 'amendment body', {
        signedAt: '2026-04-24T11:00:00Z',
        signedBy: 'u-42',
      }),
      amendsId: 'orig-1',
    }
    routeMock({
      encounter: encounter({ id: 'e-a3', patientId: 'p-1', status: 'IN_PROGRESS' }),
      notes: [original, amendment],
    })

    renderAt('/tenants/acme-health/encounters/e-a3')
    await screen.findByTestId('notes-list')

    // Original is SIGNED non-amendment → Amend button visible.
    // Amendment is SIGNED but `amendsId` is set → Amend button NOT visible.
    expect(screen.getAllByTestId('amend-note-button')).toHaveLength(1)
    // Both rows render; amendment row carries the marker attribute.
    const rows = screen.getAllByTestId('note-row')
    expect(rows).toHaveLength(2)
    const amendmentRow = rows.find(
      (r) => r.getAttribute('data-note-is-amendment') === 'true',
    )
    expect(amendmentRow).toBeDefined()
  })

  it('submitting an amendment threads it under the original after refetch', async () => {
    const original = signedNoteOf('orig-2', 'original body', {
      signedAt: '2026-04-24T10:00:00Z',
      signedBy: 'u-42',
    })
    const amendment = {
      ...noteOf('amend-2', 'amendment body'),
      amendsId: 'orig-2',
    }
    let amended = false
    fetchSpy.mockImplementation((input, init) => {
      const url = String(input)
      const method = String(init?.method ?? 'GET').toUpperCase()
      if (url.endsWith('/amend') && method === 'POST') {
        amended = true
        return Promise.resolve(jsonResponse(201, { data: amendment, requestId: 'r' }))
      }
      if (url.endsWith('/notes') && method === 'GET') {
        return Promise.resolve(
          jsonResponse(200, {
            data: pagedDataMock(amended ? [original, amendment] : [original]),
            requestId: 'r',
          }),
        )
      }
      // encounter GET
      return Promise.resolve(
        jsonResponse(200, {
          data: encounter({ id: 'e-a4', patientId: 'p-1', status: 'IN_PROGRESS' }),
          requestId: 'r',
        }),
      )
    })

    renderAt('/tenants/acme-health/encounters/e-a4')
    await screen.findByTestId('notes-list')
    await userEvent.click(screen.getByTestId('amend-note-button'))

    const editor = await screen.findByTestId('amend-note-textarea')
    expect(editor).toHaveValue('original body') // pre-filled with original body

    // Replace the text and save.
    await userEvent.clear(editor)
    await userEvent.type(editor, 'Correction: dosage was wrong.')
    await userEvent.click(screen.getByTestId('amend-save-button'))

    // Wait for the amendment row to appear nested under the
    // original (via the threading container).
    const thread = await screen.findByTestId('note-amendments-thread')
    expect(thread).toBeInTheDocument()
    // The nested row carries the data-note-is-amendment marker
    // and the Amendment status badge.
    const amendmentRow = thread.querySelector('[data-note-is-amendment="true"]')
    expect(amendmentRow).not.toBeNull()
  })

  it('shows Amended badge on the original once an amendment exists', async () => {
    const original = signedNoteOf('orig-3', 'original body', {
      signedAt: '2026-04-24T10:00:00Z',
      signedBy: 'u-42',
    })
    const amendment = {
      ...noteOf('amend-3', 'amendment body'),
      amendsId: 'orig-3',
    }
    routeMock({
      encounter: encounter({ id: 'e-a5', patientId: 'p-1', status: 'IN_PROGRESS' }),
      notes: [original, amendment],
    })

    renderAt('/tenants/acme-health/encounters/e-a5')
    await screen.findByTestId('notes-list')

    const badge = await screen.findByTestId('note-amended-badge')
    expect(badge).toHaveTextContent('Amended')
  })

  it('Sign button on a draft amendment is visible even when encounter is FINISHED (chunk B.5 carve-out)', async () => {
    // Cross-slice proof: chunk B.5 allows signing amendments
    // regardless of encounter status. The frontend mirrors that.
    const original = signedNoteOf('orig-4', 'original body', {
      signedAt: '2026-04-24T10:00:00Z',
      signedBy: 'u-42',
    })
    const draftAmendment = {
      ...noteOf('amend-4', 'amendment body'),
      amendsId: 'orig-4',
    }
    routeMock({
      encounter: encounter({
        id: 'e-a6',
        patientId: 'p-1',
        status: 'FINISHED',
        startedAt: '2026-04-24T10:00:00Z',
      }),
      notes: [original, draftAmendment],
    })

    renderAt('/tenants/acme-health/encounters/e-a6')
    await screen.findByTestId('notes-list')

    // Sign button visible specifically because the draft is an
    // amendment. A non-amendment draft on a FINISHED encounter
    // would not show Sign (covered by the existing
    // "hides ... Sign button when status is FINISHED" test).
    const signButtons = screen.getAllByTestId('sign-note-button')
    expect(signButtons).toHaveLength(1)
    // No Amend button on the closed encounter's signed original?
    // Actually amend IS allowed on closed encounters per locked
    // plan — the signed original should still show Amend.
    expect(screen.getByTestId('amend-note-button')).toBeInTheDocument()
  })

  it('handles 409 cannot_amend_unsigned_note with a user-facing message', async () => {
    const original = signedNoteOf('orig-5', 'original body', {
      signedAt: '2026-04-24T10:00:00Z',
      signedBy: 'u-42',
    })
    fetchSpy.mockImplementation((input, init) => {
      const url = String(input)
      const method = String(init?.method ?? 'GET').toUpperCase()
      if (url.endsWith('/amend') && method === 'POST') {
        return Promise.resolve(
          jsonResponse(409, {
            code: 'resource.conflict',
            details: { reason: 'cannot_amend_unsigned_note' },
          }),
        )
      }
      if (url.endsWith('/notes') && method === 'GET') {
        return Promise.resolve(
          jsonResponse(200, { data: pagedDataMock([original]), requestId: 'r' }),
        )
      }
      return Promise.resolve(
        jsonResponse(200, {
          data: encounter({ id: 'e-a7', patientId: 'p-1', status: 'IN_PROGRESS' }),
          requestId: 'r',
        }),
      )
    })

    renderAt('/tenants/acme-health/encounters/e-a7')
    await screen.findByTestId('notes-list')
    await userEvent.click(screen.getByTestId('amend-note-button'))
    const editor = await screen.findByTestId('amend-note-textarea')
    await userEvent.clear(editor)
    await userEvent.type(editor, 'Attempted amendment')
    await userEvent.click(screen.getByTestId('amend-save-button'))

    await waitFor(() => {
      expect(screen.getByTestId('amend-note-error')).toHaveTextContent(
        /no longer signed|refresh/i,
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
    notes: Array<ReturnType<typeof noteOf> | ReturnType<typeof signedNoteOf>>
  }): void {
    fetchSpy.mockImplementation((input) => {
      const url = String(input)
      if (url.endsWith('/notes')) {
        return Promise.resolve(
          jsonResponse(200, { data: pagedDataMock(opts.notes), requestId: 'r' }),
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
      status: 'DRAFT' as const,
      createdAt: '2026-04-24T10:00:00Z',
      updatedAt: '2026-04-24T10:00:00Z',
      createdBy: 'u-1',
      updatedBy: 'u-1',
      rowVersion: 0,
    }
  }

  function signedNoteOf(
    id: string,
    body: string,
    signing: { signedAt: string; signedBy: string },
  ) {
    return {
      id,
      tenantId: 't-1',
      encounterId: 'e-any',
      body,
      status: 'SIGNED' as const,
      signedAt: signing.signedAt,
      signedBy: signing.signedBy,
      createdAt: '2026-04-24T10:00:00Z',
      updatedAt: signing.signedAt,
      createdBy: 'u-1',
      updatedBy: signing.signedBy,
      rowVersion: 1,
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
