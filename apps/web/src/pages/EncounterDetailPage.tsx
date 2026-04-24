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
import { getEncounter } from '@/lib/encounters'
import { createEncounterNote, listEncounterNotes } from '@/lib/notes'
import { useAuth } from '@/providers/AuthProvider'

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
            </CardContent>
          </Card>
        )}

        {encounter && (
          <Card data-phi data-testid="encounter-notes-card">
            <CardHeader>
              <CardTitle>Notes</CardTitle>
              <CardDescription>
                Append-only. Each save creates a new note.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
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
                {notesQuery.data && notesQuery.data.items.length > 0 && (
                  <ul
                    className="flex flex-col gap-3"
                    data-testid="notes-list"
                  >
                    {notesQuery.data.items.map((n) => (
                      <li
                        key={n.id}
                        className="rounded-md border p-3"
                      >
                        <p className="text-muted-foreground mb-1 text-xs">
                          <span className="font-mono">{n.createdAt}</span> ·
                          author{' '}
                          <span className="font-mono">{n.createdBy}</span>
                        </p>
                        <p className="whitespace-pre-wrap text-sm">{n.body}</p>
                      </li>
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

function isNotFound(err: unknown): err is ApiError {
  return err instanceof ApiError && err.status === 404
}
