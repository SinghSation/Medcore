import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'

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
  listPatientEncounters,
  startEncounter,
  type Encounter,
} from '@/lib/encounters'
import { getPatient, type PatientDetail } from '@/lib/patients'
import { useAuth } from '@/providers/AuthProvider'

export function PatientDetailPage(): React.JSX.Element {
  const { slug, patientId } = useParams<{ slug: string; patientId: string }>()
  const navigate = useNavigate()
  const { signOut } = useAuth()

  const query = useQuery({
    queryKey: ['patient', slug, patientId],
    queryFn: ({ signal }) =>
      getPatient({ tenantSlug: slug!, patientId: patientId!, signal }),
    enabled: slug !== undefined && patientId !== undefined,
    retry: (failureCount, error) => {
      if (error instanceof ApiError && error.status === 404) return false
      return failureCount < 1
    },
  })

  function onSignOut(): void {
    signOut()
    navigate('/login', { replace: true })
  }

  const [startError, setStartError] = useState<string | null>(null)
  const startMutation = useMutation({
    mutationFn: () =>
      startEncounter({
        tenantSlug: slug!,
        patientId: patientId!,
        encounterClass: 'AMB',
      }),
    onSuccess: (encounter) => {
      navigate(`/tenants/${slug}/encounters/${encounter.id}`)
    },
    onError: (err) => {
      setStartError(
        err instanceof ApiError && err.status === 403
          ? 'You do not have authority to start encounters on this tenant.'
          : 'Could not start encounter. Try again.',
      )
    },
  })

  function onStartEncounter(): void {
    setStartError(null)
    startMutation.mutate()
  }

  const encountersQuery = useQuery({
    queryKey: ['encounters', 'by-patient', slug, patientId],
    queryFn: ({ signal }) =>
      listPatientEncounters({
        tenantSlug: slug!,
        patientId: patientId!,
        signal,
      }),
    enabled: slug !== undefined && patientId !== undefined,
    retry: (failureCount, error) => {
      if (error instanceof ApiError && error.status === 404) return false
      return failureCount < 1
    },
  })

  const notFound = query.isError && isNotFound(query.error)
  const patient = query.data

  return (
    <div className="min-h-screen bg-muted/30 p-6">
      <div className="mx-auto flex max-w-4xl flex-col gap-6">
        <header className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-semibold">Patient detail</h1>
            <p className="text-muted-foreground text-sm">
              Tenant: <span className="font-mono">{slug}</span>
            </p>
          </div>
          <div className="flex gap-2">
            {patient && (
              <Button
                onClick={onStartEncounter}
                disabled={startMutation.isPending || startMutation.isSuccess}
                data-testid="start-encounter-button"
              >
                {startMutation.isPending ? 'Starting…' : 'Start encounter'}
              </Button>
            )}
            <Button variant="outline" asChild>
              <Link to={`/tenants/${slug}/patients`}>Back to list</Link>
            </Button>
            <Button variant="outline" onClick={onSignOut}>
              Sign out
            </Button>
          </div>
        </header>
        {startError !== null && (
          <div
            role="alert"
            className="text-destructive text-sm"
            data-testid="start-encounter-error"
          >
            {startError}
          </div>
        )}

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
                Patient not found or not accessible.
              </p>
              <Button variant="outline" asChild>
                <Link to={`/tenants/${slug}/patients`}>Back to list</Link>
              </Button>
            </CardContent>
          </Card>
        )}

        {query.isError && !notFound && (
          <Card>
            <CardContent className="flex items-center justify-between py-6">
              <p className="text-destructive text-sm">
                Could not load patient.
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

        {patient && (
          <Card data-phi data-testid="patient-detail-card">
            <CardHeader>
              <CardTitle>
                {patient.nameFamily}, {patient.nameGiven}
              </CardTitle>
              <CardDescription>
                MRN <span className="font-mono">{patient.mrn}</span>
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-6">
              <Section title="Identity">
                <Row label="MRN" value={patient.mrn} mono />
                <Row label="MRN source" value={patient.mrnSource} />
                <Row label="Status" value={patient.status} />
                <Row
                  label="Patient ID"
                  value={patient.id}
                  mono
                  muted
                />
              </Section>

              <Section title="Name">
                <Row label="Given" value={patient.nameGiven} />
                <Row label="Family" value={patient.nameFamily} />
                {patient.nameMiddle && (
                  <Row label="Middle" value={patient.nameMiddle} />
                )}
                {patient.namePrefix && (
                  <Row label="Prefix" value={patient.namePrefix} />
                )}
                {patient.nameSuffix && (
                  <Row label="Suffix" value={patient.nameSuffix} />
                )}
                {patient.preferredName && (
                  <Row label="Preferred" value={patient.preferredName} />
                )}
              </Section>

              <Section title="Demographics">
                <Row label="Date of birth" value={patient.birthDate} />
                <Row
                  label="Administrative sex"
                  value={patient.administrativeSex}
                />
                {patient.sexAssignedAtBirth && (
                  <Row
                    label="Sex assigned at birth"
                    value={patient.sexAssignedAtBirth}
                  />
                )}
                {patient.genderIdentityCode && (
                  <Row
                    label="Gender identity"
                    value={patient.genderIdentityCode}
                  />
                )}
                {patient.preferredLanguage && (
                  <Row
                    label="Preferred language"
                    value={patient.preferredLanguage}
                  />
                )}
              </Section>

              <Section title="Audit trail" muted>
                <Row
                  label="Created"
                  value={patient.createdAt}
                  mono
                  muted
                />
                <Row
                  label="Updated"
                  value={patient.updatedAt}
                  mono
                  muted
                />
                <Row
                  label="Row version"
                  value={String(patient.rowVersion)}
                  muted
                />
              </Section>
            </CardContent>
          </Card>
        )}

        {patient && (
          <Card data-phi data-testid="patient-encounters-card">
            <CardHeader>
              <CardTitle>Encounters</CardTitle>
              <CardDescription>
                All visits for this patient, newest first.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              {encountersQuery.isLoading && (
                <p className="text-muted-foreground text-sm">
                  Loading encounters…
                </p>
              )}
              {encountersQuery.isError && (
                <p className="text-destructive text-sm">
                  Could not load encounters.
                </p>
              )}
              {encountersQuery.data &&
                encountersQuery.data.items.length === 0 && (
                  <p
                    className="text-muted-foreground text-sm"
                    data-testid="patient-encounters-empty"
                  >
                    No encounters yet.
                  </p>
                )}
              {encountersQuery.data &&
                encountersQuery.data.items.length > 0 && (
                  <ul
                    className="flex flex-col gap-2"
                    data-testid="patient-encounters-list"
                  >
                    {encountersQuery.data.items.map((e) => (
                      <EncounterRow
                        key={e.id}
                        tenantSlug={slug!}
                        encounter={e}
                      />
                    ))}
                  </ul>
                )}
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  )
}

function EncounterRow({
  tenantSlug,
  encounter,
}: {
  tenantSlug: string
  encounter: Encounter
}): React.JSX.Element {
  const active = encounter.status === 'IN_PROGRESS'
  return (
    <li
      className={
        active
          ? 'border-primary/50 bg-primary/5 flex items-center justify-between rounded-md border p-3'
          : 'flex items-center justify-between rounded-md border p-3'
      }
      data-testid="patient-encounter-row"
      data-encounter-status={encounter.status}
    >
      <div className="flex flex-col gap-1">
        <div className="flex items-center gap-2 text-sm">
          <span
            className={
              active
                ? 'bg-primary/15 text-primary rounded-full px-2 py-0.5 text-xs font-semibold'
                : 'bg-muted text-muted-foreground rounded-full px-2 py-0.5 text-xs font-semibold'
            }
          >
            {encounter.status}
          </span>
          <span className="font-mono text-xs">{encounter.encounterClass}</span>
        </div>
        <div className="text-muted-foreground text-xs">
          Started{' '}
          <span className="font-mono">{encounter.startedAt ?? '—'}</span>
          {encounter.finishedAt && (
            <>
              {' · '}Finished{' '}
              <span className="font-mono">{encounter.finishedAt}</span>
            </>
          )}
        </div>
      </div>
      <Link
        to={`/tenants/${encodeURIComponent(tenantSlug)}/encounters/${encodeURIComponent(encounter.id)}`}
        className="text-sm hover:underline"
        data-testid="patient-encounter-link"
      >
        Open →
      </Link>
    </li>
  )
}

function Section({
  title,
  muted,
  children,
}: {
  title: string
  muted?: boolean
  children: React.ReactNode
}): React.JSX.Element {
  return (
    <div className={muted ? 'opacity-75' : ''}>
      <h2
        className={
          muted
            ? 'text-muted-foreground mb-2 text-xs font-medium uppercase tracking-wide'
            : 'text-foreground mb-2 text-sm font-semibold uppercase tracking-wide'
        }
      >
        {title}
      </h2>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        {children}
      </dl>
    </div>
  )
}

function Row({
  label,
  value,
  mono,
  muted,
}: {
  label: string
  value: string
  mono?: boolean
  muted?: boolean
}): React.JSX.Element {
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd
        className={[
          mono ? 'font-mono' : '',
          muted ? 'text-muted-foreground text-xs' : '',
        ]
          .filter(Boolean)
          .join(' ')}
      >
        {value}
      </dd>
    </>
  )
}

function isNotFound(err: unknown): err is ApiError {
  return err instanceof ApiError && err.status === 404
}

// Unused type re-export so casual readers see the wire shape lives here.
export type { PatientDetail }
