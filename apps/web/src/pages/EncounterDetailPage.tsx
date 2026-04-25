import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { AllergyBanner } from '@/components/AllergyBanner'
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
  amendEncounterNote,
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
 * Human-readable cancel-reason label. Falls back to the raw
 * closed-enum token for unknown values (future schema expansion
 * that this client predates).
 */
function cancelReasonLabel(reason: CancelReason | undefined): string {
  if (reason === undefined) return '—'
  return CANCEL_REASONS.find((r) => r.value === reason)?.label ?? reason
}

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

  // --- Note amendments (Phase 4D.6) ---
  //
  // One-amendment-editor-at-a-time. `amendingNoteId` identifies
  // which signed original is currently being amended; the
  // editor textarea + save/cancel buttons live inside that
  // row. Saving creates a NEW DRAFT note that references the
  // original via amends_id; the list refetch threads it under
  // the original.
  //
  // Amendments are signed via the existing 4D.5 sign mutation
  // above — chunk B.5 carved out the closed-encounter rule for
  // amendments, so the Sign button on a draft amendment works
  // even when the parent encounter is FINISHED / CANCELLED.

  const [amendingNoteId, setAmendingNoteId] = useState<string | null>(null)
  const [amendDraft, setAmendDraft] = useState('')
  const [amendError, setAmendError] = useState<string | null>(null)
  const amendMutation = useMutation({
    mutationFn: (vars: { noteId: string; body: string }) =>
      amendEncounterNote({
        tenantSlug: slug!,
        encounterId: encounterId!,
        noteId: vars.noteId,
        body: vars.body,
      }),
    onSuccess: () => {
      setAmendingNoteId(null)
      setAmendDraft('')
      setAmendError(null)
      queryClient.invalidateQueries({
        queryKey: ['notes', slug, encounterId],
      })
    },
    onError: (err) => {
      if (err instanceof ApiError && err.status === 403) {
        setAmendError('You do not have authority to amend notes on this tenant.')
      } else if (err instanceof ApiError && err.status === 409) {
        const reason = extractConflictReason(err)
        setAmendError(
          reason === 'cannot_amend_unsigned_note'
            ? 'The original note is no longer signed. Refresh to reconcile.'
            : reason === 'cannot_amend_an_amendment'
              ? 'Amendments cannot themselves be amended.'
              : 'This amendment conflicts with the current note state.',
        )
        queryClient.invalidateQueries({
          queryKey: ['notes', slug, encounterId],
        })
      } else {
        setAmendError('Could not save amendment. Try again.')
      }
    },
  })

  function onStartAmend(noteId: string, originalBody: string): void {
    setAmendError(null)
    // Pre-fill the editor with the original body for context;
    // the user edits it into the corrected form. The wire model
    // is "amendment is a complete new note," not a diff against
    // the original (matches FHIR DocumentReference.relatesTo).
    setAmendDraft(originalBody)
    setAmendingNoteId(noteId)
  }

  function onCancelAmend(): void {
    setAmendingNoteId(null)
    setAmendDraft('')
    setAmendError(null)
  }

  function onSaveAmend(): void {
    if (amendingNoteId === null) return
    const body = amendDraft.trim()
    if (body.length === 0) return
    setAmendError(null)
    amendMutation.mutate({ noteId: amendingNoteId, body })
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

  // Phase 4D.6 threading: split the flat note list into
  // top-level originals + an `amendsId → amendments[]` map.
  // Memoized so unrelated re-renders don't re-walk items.
  // Sibling amendments are ordered by createdAt asc (oldest
  // first) so the thread reads chronologically downward.
  const { originals, amendmentsByParent } = useMemo(() => {
    const items = notesQuery.data?.items ?? []
    const map = new Map<string, EncounterNote[]>()
    const orig: EncounterNote[] = []
    for (const n of items) {
      if (n.amendsId) {
        const list = map.get(n.amendsId) ?? []
        list.push(n)
        map.set(n.amendsId, list)
      } else {
        orig.push(n)
      }
    }
    for (const list of map.values()) {
      list.sort((a, b) => a.createdAt.localeCompare(b.createdAt))
    }
    return { originals: orig, amendmentsByParent: map }
  }, [notesQuery.data?.items])

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
                    <dd data-testid="encounter-cancel-reason">
                      {cancelReasonLabel(encounter.cancelReason)}
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
                </div>
              )}
              {/* Lifecycle error banner is rendered OUTSIDE the
                  IN_PROGRESS conditional — on a 409
                  `encounter_already_closed`, the refetch flips
                  status to FINISHED/CANCELLED and the lifecycle
                  actions block unmounts. Keeping the banner at
                  card scope means the user still sees WHY their
                  attempt failed after the state flipped.
                  CodeRabbit review on PR #2 flagged the
                  disappearing-banner issue. */}
              {lifecycleError !== null && (
                <p
                  role="alert"
                  className="text-destructive mt-4 text-xs"
                  data-testid="encounter-lifecycle-error"
                >
                  {lifecycleError}
                </p>
              )}
            </CardContent>
          </Card>
        )}

        {encounter && (
          <AllergyBanner
            tenantSlug={slug!}
            patientId={encounter.patientId}
          />
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
                    {originals.map((n) => (
                      <NoteRow
                        key={n.id}
                        note={n}
                        amendments={amendmentsByParent.get(n.id) ?? []}
                        encounterStatus={encounter.status}
                        onSign={onSignNote}
                        pendingSignNoteId={signingNoteId}
                        onStartAmend={onStartAmend}
                        amendingNoteId={amendingNoteId}
                        amendDraft={amendDraft}
                        onAmendDraftChange={setAmendDraft}
                        onSaveAmend={onSaveAmend}
                        onCancelAmend={onCancelAmend}
                        amendIsPending={amendMutation.isPending}
                        amendError={amendError}
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

/**
 * Note row — renders a top-level note plus, optionally, the
 * threaded amendments that reference it via `amends_id`. Used
 * for both originals and (recursively, but always single-level
 * per the locked 4D.6 plan + V23 trigger) for amendments
 * displayed as nested rows.
 *
 * Two visibility rules at this level (mirrors the backend
 * contract from chunks B + B.5):
 *
 *   1. **Amend button** — visible on a SIGNED non-amendment
 *      note (`status === 'SIGNED' && !note.amendsId`). Hidden
 *      on DRAFT (cannot amend an unsigned note) and on
 *      amendments (single-level chain). Encounter status does
 *      NOT affect Amend visibility — the whole point is that
 *      closed encounters CAN be amended.
 *
 *   2. **Sign button** — visible on a DRAFT note. For non-
 *      amendments the parent encounter must be IN_PROGRESS
 *      (4C.5 rule). For amendments (`note.amendsId != null`)
 *      sign is allowed regardless of the parent encounter's
 *      status — that's the 4D.6 chunk B.5 carve-out.
 */
function NoteRow({
  note,
  amendments,
  encounterStatus,
  onSign,
  pendingSignNoteId,
  onStartAmend,
  amendingNoteId,
  amendDraft,
  onAmendDraftChange,
  onSaveAmend,
  onCancelAmend,
  amendIsPending,
  amendError,
  nested = false,
}: {
  note: EncounterNote
  amendments?: EncounterNote[]
  encounterStatus: 'PLANNED' | 'IN_PROGRESS' | 'FINISHED' | 'CANCELLED'
  onSign: (noteId: string) => void
  pendingSignNoteId: string | null
  onStartAmend: (noteId: string, originalBody: string) => void
  amendingNoteId: string | null
  amendDraft: string
  onAmendDraftChange: (value: string) => void
  onSaveAmend: () => void
  onCancelAmend: () => void
  amendIsPending: boolean
  amendError: string | null
  nested?: boolean
}): React.JSX.Element {
  const status = note.status ?? 'DRAFT'
  const isSigned = status === 'SIGNED'
  const isAmendment = note.amendsId !== undefined && note.amendsId !== null
  const isPendingSign = pendingSignNoteId === note.id
  const isAmendingThisRow = amendingNoteId === note.id
  const childAmendments = amendments ?? []
  const hasAmendments = childAmendments.length > 0

  // Sign visibility — allows signing amendments on closed
  // encounters per chunk B.5; non-amendment drafts still
  // require IN_PROGRESS.
  const showSignButton =
    !isSigned && (isAmendment || encounterStatus === 'IN_PROGRESS')

  // Amend visibility — only on SIGNED non-amendments. The
  // chain rule (no amend-of-amendment) is enforced both here
  // and at the API + V23 trigger.
  const showAmendButton = isSigned && !isAmendment

  return (
    <li
      className={
        nested
          ? 'border-l-primary/30 ml-6 rounded-md border border-l-4 p-3'
          : isSigned
            ? 'border-primary/40 bg-primary/5 rounded-md border p-3'
            : 'rounded-md border p-3'
      }
      data-testid="note-row"
      data-note-status={status}
      data-note-is-amendment={isAmendment ? 'true' : 'false'}
    >
      <div className="mb-1 flex items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
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
          {isAmendment && (
            <span
              className="bg-amber-500/15 text-amber-700 dark:text-amber-300 rounded-full px-2 py-0.5 text-xs font-semibold"
              data-testid="note-amendment-badge"
            >
              Amendment
            </span>
          )}
          {hasAmendments && (
            <span
              className="bg-secondary text-secondary-foreground rounded-full px-2 py-0.5 text-xs font-semibold"
              data-testid="note-amended-badge"
            >
              Amended
            </span>
          )}
          <span className="text-muted-foreground text-xs">
            <span className="font-mono">{note.createdAt}</span> · author{' '}
            <span className="font-mono">{note.createdBy}</span>
          </span>
        </div>
        <div className="flex gap-2">
          {showSignButton && (
            <Button
              size="sm"
              variant="outline"
              onClick={() => onSign(note.id)}
              disabled={isPendingSign}
              data-testid="sign-note-button"
            >
              {isPendingSign ? 'Signing…' : 'Sign'}
            </Button>
          )}
          {showAmendButton && !isAmendingThisRow && (
            <Button
              size="sm"
              variant="outline"
              onClick={() => onStartAmend(note.id, note.body)}
              data-testid="amend-note-button"
            >
              Amend
            </Button>
          )}
        </div>
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

      {isAmendingThisRow && (
        <div
          className="mt-3 flex flex-col gap-2 rounded-md border p-3"
          data-testid="amend-note-editor"
        >
          <label
            htmlFor={`amend-body-${note.id}`}
            className="text-muted-foreground text-xs font-medium uppercase tracking-wide"
          >
            Amendment body
          </label>
          <textarea
            id={`amend-body-${note.id}`}
            data-testid="amend-note-textarea"
            value={amendDraft}
            onChange={(e) => onAmendDraftChange(e.target.value)}
            disabled={amendIsPending}
            rows={4}
            maxLength={20000}
            placeholder="Write the amendment…"
            className="border-input focus-visible:border-ring focus-visible:ring-ring/50 w-full rounded-md border bg-transparent px-3 py-2 text-sm shadow-xs outline-none focus-visible:ring-[3px] disabled:cursor-not-allowed disabled:opacity-50"
          />
          {amendError !== null && (
            <p
              role="alert"
              className="text-destructive text-xs"
              data-testid="amend-note-error"
            >
              {amendError}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <Button
              size="sm"
              variant="outline"
              onClick={onCancelAmend}
              disabled={amendIsPending}
              data-testid="amend-cancel-button"
            >
              Cancel
            </Button>
            <Button
              size="sm"
              onClick={onSaveAmend}
              disabled={amendIsPending || amendDraft.trim().length === 0}
              data-testid="amend-save-button"
            >
              {amendIsPending ? 'Saving…' : 'Save amendment'}
            </Button>
          </div>
        </div>
      )}

      {hasAmendments && (
        <ul
          className="mt-3 flex flex-col gap-2"
          data-testid="note-amendments-thread"
        >
          {childAmendments.map((child) => (
            <NoteRow
              key={child.id}
              note={child}
              encounterStatus={encounterStatus}
              onSign={onSign}
              pendingSignNoteId={pendingSignNoteId}
              onStartAmend={onStartAmend}
              amendingNoteId={amendingNoteId}
              amendDraft={amendDraft}
              onAmendDraftChange={onAmendDraftChange}
              onSaveAmend={onSaveAmend}
              onCancelAmend={onCancelAmend}
              amendIsPending={amendIsPending}
              amendError={amendError}
              nested
            />
          ))}
        </ul>
      )}
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
