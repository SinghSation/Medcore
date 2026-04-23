import { Link, useNavigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'

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
      </div>
    </div>
  )
}

function isNotFound(err: unknown): err is ApiError {
  return err instanceof ApiError && err.status === 404
}
