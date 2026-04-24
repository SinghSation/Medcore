import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { ApiError } from '@/lib/api-client'
import {
  cancelEncounter,
  finishEncounter,
  getEncounter,
  type CancelReason,
} from '@/lib/encounters'
import {
  createEncounterNote,
  listEncounterNotes,
  signEncounterNote,
  type EncounterNote,
} from '@/lib/notes'
import { useAuth } from '@/providers/AuthProvider'

const CANCEL_REASONS: ReadonlyArray<{ value: CancelReason; label: string }> = [
  { value: 'NO_SHOW', label: 'No-show' },
  { value: 'PATIENT_DECLINED', label: 'Patient declined' },
  { value: 'SCHEDULING_ERROR', label: 'Scheduling error' },
  { value: 'OTHER', label: 'Other' },
]

/**
 * Minimal encounter detail surface (Phase 4C.1, VS1 Chunk D).
 *
 * Deliberately narrow per the approved constraint: shows ONLY
 * status, class, started_at, finished_at, and a link back to
 * the patient. Verbose audit metadata, state transitions, and
 * provider attribution are future slices.
 */
export function EncounterDetailPage(): React.JSX.Element {
  const { slug, encounterId } = useParams<{
    slug: string
    encounterId: string
  }>()
  const navigate = useNavigate()
  const { signOut } = useAuth()

  const query = useQuery({
    queryKey: ['encounter', slug, encounterId],
    queryFn: ({ signal }) =>
      getEncounter({
        tenantSlug: slug!,
        encounterId: encounterId!,
        signal,
      }),
    enabled: slug !== undefined && encounterId !== undefined,
    retry: (failureCount, error) => {
      if (error instanceof ApiError && error.status === 404) return false
      return failureCount < 1
    },
  })

  function onSignOut(): void {
    signOut()
    navigate('/login', { replace: true })
  }

  // --- Notes (Phase 4D.1) ---

  const queryClient = useQueryClient()
  const notesQuery = useQuery({
    queryKey: ['notes', slug, encounterId],
    queryFn: ({ signal }) =>
      listEncounterNotes({
        tenantSlug: slug!,
        encounterId: encounterId!,
        signal,
      }),
    enabled: slug !== undefined && encounterId !== undefined,
    retry: (failureCount, error) => {
      if (error instanceof ApiError && error.status === 404) return false
      return failureCount < 1
    },
  })

  const [noteDraft, setNoteDraft] = useState('')
  const [saveError, setSaveError] = useState<string | null>(null)
  const saveMutation = useMutation({
    mutationFn: (body: string) =>
      createEncounterNote({
        tenantSlug: slug!,
        encounterId: encounterId!,
        body,
      }),
    onSuccess: () => {
      setNoteDraft('')
      setSaveError(null)
      queryClient.invalidateQueries({
        queryKey: ['notes', slug, encounterId],
      })
    },
    onError: (err) => {
      setSaveError(
        err instanceof ApiError && err.status === 403
          ? 'You do not have authority to write notes on this tenant.'
          : 'Could not save note. Try again.',
      )
    },
  })

  function onSaveNote(): void {
    const body = noteDraft.trim()
    if (body.length === 0) return
    setSaveError(null)
    saveMutation.mutate(body)
  }

  const canSave =
    noteDraft.trim().length > 0 && !saveMutation.isPending

  // --- Note signing (Phase 4D.5) ---

  const [signError, setSignError] = useState<string | null>(null)
  const [signingNoteId, setSigningNoteId] = useState<string | null>(null)
  const signMutation = useMutation({
    mutationFn: (noteId: string) =>
      signEncounterNote({
        tenantSlug: slug!,
        encounterId: encounterId!,
        noteId,
      }),
    onSuccess: () => {
      setSignError(null)
      setSigningNoteId(null)
      queryClient.invalidateQueries({
        queryKey: ['notes', slug, encounterId],
      })
    },
    onError: (err) => {
      setSigningNoteId(null)
      if (err instanceof ApiError && err.status === 403) {
        setSignError('You do not have authority to sign notes on this tenant.')
      } else if (err instanceof ApiError && err.status === 409) {
        // Someone else signed, or the list is stale. Refetch to
        // reconcile and show a brief banner.
        setSignError('This note was already signed.')
        queryClient.invalidateQueries({
          queryKey: ['notes', slug, encounterId],
        })
      } else {
        setSignError('Could not sign note. Try again.')
      }
    },
  })

  function onSignNote(noteId: string): void {
    setSignError(null)
    setSigningNoteId(noteId)
    signMutation.mutate(noteId)
  }

  // --- Encounter lifecycle: finish + cancel (Phase 4C.5) ---

  const [lifecycleError, setLifecycleError] = useState<string | null>(null)
  const [cancelPickerOpen, setCancelPickerOpen] = useState(false)
  const [cancelReason, setCancelReason] = useState<CancelReason>('NO_SHOW')

  const finishMutation = useMutation({
    mutationFn: () =>
      finishEncounter({
        tenantSlug: slug!,
        encounterId: encounterId!,
      }),
    onSuccess: () => {
      setLifecycleError(null)
      queryClient.invalidateQueries({
        queryKey: ['encounter', slug, encounterId],
      })
      queryClient.invalidateQueries({
        queryKey: ['notes', slug, encounterId],
      })
    },
    onError: (err) => {
      if (err instanceof ApiError && err.status === 403) {
        setLifecycleError(
          'You do not have authority to finish encounters on this tenant.',
        )
      } else if (err instanceof ApiError && err.status === 409) {
        const reason = extractConflictReason(err)
        setLifecycleError(
          reason === 'encounter_has_no_signed_notes'
            ? 'Finish requires at least one signed note on the encounter.'
            : 'This encounter is already closed.',
        )
        queryClient.invalidateQueries({
          queryKey: ['encounter', slug, encounterId],
        })
      } else {
        setLifecycleError('Could not finish encounter. Try again.')
      }
    },
  })

  const cancelMutation = useMutation({
    mutationFn: (reason: CancelReason) =>
      cancelEncounter({
        tenantSlug: slug!,
        encounterId: encounterId!,
        cancelReason: reason,
      }),
    onSuccess: () => {
      setLifecycleError(null)
      setCancelPickerOpen(false)
      queryClient.invalidateQueries({
        queryKey: ['encounter', slug, encounterId],
      })
    },
    onError: (err) => {
      if (err instanceof ApiError && err.status === 403) {
        setLifecycleError(
          'You do not have authority to cancel encounters on this tenant.',
        )
      } else if (err instanceof ApiError && err.status === 409) {
        setLifecycleError('This encounter is already closed.')
        queryClient.invalidateQueries({
          queryKey: ['encounter', slug, encounterId],
        })
      } else {
        setLifecycleError('Could not cancel encounter. Try again.')
      }
    },
  })

  function onFinishEncounter(): void {
    setLifecycleError(null)
    finishMutation.mutate()
  }

  function onConfirmCancel(): void {
    setLifecycleError(null)
    cancelMutation.mutate(cancelReason)
  }

  const signedCount =
    notesQuery.data?.items.filter((n) => (n.status ?? 'DRAFT') === 'SIGNED')
      .length ?? 0
  const canFinish = signedCount > 0 && !finishMutation.isPending

  const notFound = query.isError && isNotFound(query.error)
  const encounter = query.data

  return (
    <div className="min-h-screen bg-muted/30 p-6">
      <div className="mx-auto flex max-w-3xl flex-col gap-6">
        <header className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-semibold">Encounter</h1>
            <p className="text-muted-foreground text-sm">
              Tenant: <span className="font-mono">{slug}</span>
            </p>
          </div>
          <div className="flex gap-2">
            <Button variant="outline" asChild>
              <Link to={`/tenants/${slug}/patients`}>Back to patients</Link>
            </Button>
            <Button variant="outline" onClick={onSignOut}>
              Sign out
            </Button>
          </div>
        </header>

        {query.isLoading && (
          <Card>
            <CardContent className="py-6">
              <p className="text-muted-foreground text-sm">Loading…</p>
            </CardContent>
          </Card>
        )}

        {notFound && (
          <Card>
            <CardContent className="flex items-center justify-between py-6">
              <p className="text-muted-foreground text-sm">
                Encounter not found or not accessible.
              </p>
              <Button variant="outline" asChild>
                <Link to={`/tenants/${slug}/patients`}>Back to patients</Link>
              </Button>
            </CardContent>
          </Card>
        )}

        {query.isError && !notFound && (
          <Card>
            <CardContent className="flex items-center justify-between py-6">
              <p className="text-destructive text-sm">
                Could not load encounter.
              </p>
              <Button
                variant="outline"
                size="sm"
                onClick={() => query.refetch()}
              >
                Retry
              </Button>
            </CardContent>
          </Card>
        )}

        {encounter && (
          <Card data-phi data-testid="encounter-detail-card">
            <CardHeader>
              <CardTitle>{encounter.status}</CardTitle>
              <CardDescription>
                Class <span className="font-mono">{encounter.encounterClass}</span>
              </CardDescription>
            </CardHeader>
            <CardContent>
              <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
                <dt className="text-muted-foreground">Status</dt>
                <dd>{encounter.status}</dd>

                <dt className="text-muted-foreground">Class</dt>
                <dd className="font-mono">{encounter.encounterClass}</dd>

                <dt className="text-muted-foreground">Started</dt>
                <dd className="font-mono">{encounter.startedAt ?? '—'}</dd>

                <dt className="text-muted-foreground">Finished</dt>
                <dd className="font-mono">{encounter.finishedAt ?? '—'}</dd>

                {encounter.status === 'CANCELLED' && (
                  <>
                    <dt className="text-muted-foreground">Cancelled</dt>
                    <dd
                      className="font-mono"
                      data-testid="encounter-cancelled-at"
                    >
                      {encounter.cancelledAt ?? '—'}
                    </dd>
                    <dt className="text-muted-foreground">Cancel reason</dt>
                    <dd
                      className="font-mono"
                      data-testid="encounter-cancel-reason"
                    >
                      {encounter.cancelReason ?? '—'}
                    </dd>
                  </>
                )}

                <dt className="text-muted-foreground">Patient</dt>
                <dd>
                  <Link
                    to={`/tenants/${slug}/patients/${encounter.patientId}`}
                    className="hover:underline"
                    data-testid="encounter-patient-link"
                  >
                    View patient detail →
                  </Link>
                </dd>
              </dl>

              {/* Lifecycle actions (Phase 4C.5) — visible only while
                  the encounter is IN_PROGRESS. */}
              {encounter.status === 'IN_PROGRESS' && (
                <div
                  className="mt-4 flex flex-col gap-2"
                  data-testid="encounter-lifecycle-actions"
                >
                  <div className="flex gap-2">
                    <Button
                      variant="default"
                      size="sm"
                      onClick={onFinishEncounter}
                      disabled={!canFinish}
                      data-testid="finish-encounter-button"
                    >
                      {finishMutation.isPending ? 'Finishing…' : 'Finish encounter'}
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setCancelPickerOpen((v) => !v)}
                      data-testid="cancel-encounter-button"
                    >
                      Cancel encounter
                    </Button>
                  </div>
                  {!canFinish && signedCount === 0 && (
                    <p
                      className="text-muted-foreground text-xs"
                      data-testid="finish-encounter-helper"
                    >
                      Finish requires at least one signed note.
                    </p>
                  )}
                  {cancelPickerOpen && (
                    <div
                      className="flex flex-col gap-2 rounded-md border p-3"
                      data-testid="cancel-encounter-picker"
                    >
                      <label
                        htmlFor="cancel-reason"
                        className="text-muted-foreground text-xs font-medium uppercase tracking-wide"
                      >
                        Cancel reason
                      </label>
                      <select
                        id="cancel-reason"
                        value={cancelReason}
                        onChange={(e) =>
                          setCancelReason(e.target.value as CancelReason)
                        }
                        data-testid="cancel-reason-select"
                        className="border-input w-full rounded-md border bg-transparent px-3 py-2 text-sm"
                      >
                        {CANCEL_REASONS.map((r) => (
                          <option key={r.value} value={r.value}>
                            {r.label}
                          </option>
                        ))}
                      </select>
                      <div className="flex justify-end gap-2">
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => setCancelPickerOpen(false)}
                        >
                          Dismiss
                        </Button>
                        <Button
                          variant="default"
                          size="sm"
                          onClick={onConfirmCancel}
                          disabled={cancelMutation.isPending}
                          data-testid="confirm-cancel-button"
                        >
                          {cancelMutation.isPending
                            ? 'Cancelling…'
                            : 'Confirm cancel'}
                        </Button>
                      </div>
                    </div>
                  )}
                  {lifecycleError !== null && (
                    <p
                      role="alert"
                      className="text-destructive text-xs"
                      data-testid="encounter-lifecycle-error"
                    >
                      {lifecycleError}
                    </p>
                  )}
                </div>
              )}
            </CardContent>
          </Card>
        )}

        {encounter && (
          <Card data-phi data-testid="encounter-notes-card">
            <CardHeader>
              <CardTitle>Notes</CardTitle>
              <CardDescription>
                {encounter.status === 'IN_PROGRESS'
                  ? 'Append-only. Each save creates a new note.'
                  : 'This encounter is closed. Notes are read-only.'}
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
              {encounter.status === 'IN_PROGRESS' && (
                <>
                  <div className="flex flex-col gap-2">
                    <label
                      htmlFor="note-body"
                      className="text-muted-foreground text-xs font-medium uppercase tracking-wide"
                    >
                      New note
                    </label>
                    <textarea
                      id="note-body"
                      data-testid="note-body-textarea"
                      value={noteDraft}
                      onChange={(e) => setNoteDraft(e.target.value)}
                      disabled={saveMutation.isPending}
                      rows={4}
                      maxLength={20000}
                      placeholder="Write a clinical note…"
                      className="border-input focus-visible:border-ring focus-visible:ring-ring/50 w-full rounded-md border bg-transparent px-3 py-2 text-sm shadow-xs outline-none focus-visible:ring-[3px] disabled:cursor-not-allowed disabled:opacity-50"
                    />
                    {saveError !== null && (
                      <p
                        role="alert"
                        className="text-destructive text-xs"
                        data-testid="save-note-error"
                      >
                        {saveError}
                      </p>
                    )}
                    <div className="flex justify-end">
                      <Button
                        onClick={onSaveNote}
                        disabled={!canSave}
                        data-testid="save-note-button"
                      >
                        {saveMutation.isPending ? 'Saving…' : 'Save note'}
                      </Button>
                    </div>
                  </div>

                  <hr className="border-border" />
                </>
              )}

              <div className="flex flex-col gap-3">
                {notesQuery.isLoading && (
                  <p className="text-muted-foreground text-sm">Loading notes…</p>
                )}
                {notesQuery.isError && (
                  <p className="text-destructive text-sm">
                    Could not load notes.
                  </p>
                )}
                {notesQuery.data && notesQuery.data.items.length === 0 && (
                  <p
                    className="text-muted-foreground text-sm"
                    data-testid="notes-empty"
                  >
                    No notes yet.
                  </p>
                )}
                {signError !== null && (
                  <p
                    role="alert"
                    className="text-destructive text-xs"
                    data-testid="sign-note-error"
                  >
                    {signError}
                  </p>
                )}
                {notesQuery.data && notesQuery.data.items.length > 0 && (
                  <ul
                    className="flex flex-col gap-3"
                    data-testid="notes-list"
                  >
                    {notesQuery.data.items.map((n) => (
                      <NoteRow
                        key={n.id}
                        note={n}
                        onSign={onSignNote}
                        pendingSignNoteId={signingNoteId}
                        canSign={encounter.status === 'IN_PROGRESS'}
                      />
                    ))}
                  </ul>
                )}
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  )
}

function NoteRow({
  note,
  onSign,
  pendingSignNoteId,
  canSign,
}: {
  note: EncounterNote
  onSign: (noteId: string) => void
  pendingSignNoteId: string | null
  canSign: boolean
}): React.JSX.Element {
  const status = note.status ?? 'DRAFT'
  const isSigned = status === 'SIGNED'
  const isPending = pendingSignNoteId === note.id
  const showSignButton = !isSigned && canSign
  return (
    <li
      className={
        isSigned
          ? 'border-primary/40 bg-primary/5 rounded-md border p-3'
          : 'rounded-md border p-3'
      }
      data-testid="note-row"
      data-note-status={status}
    >
      <div className="mb-1 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span
            className={
              isSigned
                ? 'bg-primary/15 text-primary rounded-full px-2 py-0.5 text-xs font-semibold'
                : 'bg-muted text-muted-foreground rounded-full px-2 py-0.5 text-xs font-semibold'
            }
            data-testid="note-status-badge"
          >
            {isSigned ? 'Signed' : 'Draft'}
          </span>
          <span className="text-muted-foreground text-xs">
            <span className="font-mono">{note.createdAt}</span> · author{' '}
            <span className="font-mono">{note.createdBy}</span>
          </span>
        </div>
        {showSignButton && (
          <Button
            size="sm"
            variant="outline"
            onClick={() => onSign(note.id)}
            disabled={isPending}
            data-testid="sign-note-button"
          >
            {isPending ? 'Signing…' : 'Sign'}
          </Button>
        )}
      </div>
      {isSigned && (
        <p
          className="text-muted-foreground mb-1 text-xs"
          data-testid="note-signed-attribution"
        >
          Signed on <span className="font-mono">{note.signedAt}</span> by{' '}
          <span className="font-mono">{note.signedBy}</span>
        </p>
      )}
      <p className="whitespace-pre-wrap text-sm">{note.body}</p>
    </li>
  )
}

function isNotFound(err: unknown): err is ApiError {
  return err instanceof ApiError && err.status === 404
}

/**
 * Pull `details.reason` out of a 409 `resource.conflict` body.
 * Returns `null` when the body doesn't carry one (unexpected
 * shape). Used by the finish/cancel mutations to distinguish
 * `encounter_has_no_signed_notes` from `encounter_already_closed`.
 */
function extractConflictReason(err: ApiError): string | null {
  if (typeof err.body !== 'object' || err.body === null) return null
  const details = (err.body as { details?: unknown }).details
  if (typeof details !== 'object' || details === null) return null
  const reason = (details as { reason?: unknown }).reason
  return typeof reason === 'string' ? reason : null
}
