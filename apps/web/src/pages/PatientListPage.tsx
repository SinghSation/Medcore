import * as React from 'react'
import { Link, useParams, useNavigate } from 'react-router-dom'
import { useQuery, keepPreviousData } from '@tanstack/react-query'

import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { listPatients } from '@/lib/patients'
import { useAuth } from '@/providers/AuthProvider'

const PAGE_SIZE = 20

export function PatientListPage(): React.JSX.Element {
  const { slug } = useParams<{ slug: string }>()
  const navigate = useNavigate()
  const { signOut } = useAuth()
  const [offset, setOffset] = React.useState(0)

  const query = useQuery({
    queryKey: ['patients', slug, { limit: PAGE_SIZE, offset }],
    queryFn: ({ signal }) =>
      listPatients({
        tenantSlug: slug!,
        limit: PAGE_SIZE,
        offset,
        signal,
      }),
    enabled: slug !== undefined,
    placeholderData: keepPreviousData,
  })

  function onSignOut(): void {
    signOut()
    navigate('/login', { replace: true })
  }

  const page = query.data
  const shown = page ? page.items.length : 0
  const from = shown === 0 ? 0 : offset + 1
  const to = offset + shown

  return (
    <div className="min-h-screen bg-muted/30 p-6">
      <div className="mx-auto flex max-w-5xl flex-col gap-6">
        <header className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-semibold">Patients</h1>
            <p className="text-muted-foreground text-sm">
              Tenant: <span className="font-mono">{slug}</span>
            </p>
          </div>
          <div className="flex gap-2">
            <Button variant="outline" asChild>
              <Link to="/">Back to tenants</Link>
            </Button>
            <Button variant="outline" onClick={onSignOut}>
              Sign out
            </Button>
          </div>
        </header>

        <Card>
          <CardHeader>
            <CardTitle>Patient list</CardTitle>
            <CardDescription>
              Showing {from}–{to} of {page?.totalCount ?? 0}
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {query.isLoading && (
              <p className="text-muted-foreground text-sm">Loading…</p>
            )}
            {query.isError && (
              <div className="flex items-center gap-3">
                <p className="text-destructive text-sm">
                  Could not load patients.
                </p>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => query.refetch()}
                >
                  Retry
                </Button>
              </div>
            )}
            {page && page.items.length === 0 && (
              <p className="text-muted-foreground text-sm">
                No patients yet in this tenant.
              </p>
            )}
            {page && page.items.length > 0 && (
              <div className="overflow-x-auto">
                <table
                  className="w-full text-left text-sm"
                  data-testid="patient-list-table"
                >
                  <thead className="text-muted-foreground border-b text-xs uppercase">
                    <tr>
                      <th className="py-2 pr-4 font-medium">MRN</th>
                      <th className="py-2 pr-4 font-medium">Family</th>
                      <th className="py-2 pr-4 font-medium">Given</th>
                      <th className="py-2 pr-4 font-medium">DOB</th>
                      <th className="py-2 pr-4 font-medium">Sex</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.items.map((p) => (
                      <tr
                        key={p.id}
                        className="hover:bg-accent/30 border-b last:border-b-0"
                      >
                        <td className="py-2 pr-4 font-mono text-xs">{p.mrn}</td>
                        <td className="py-2 pr-4 font-medium">{p.nameFamily}</td>
                        <td className="py-2 pr-4">{p.nameGiven}</td>
                        <td className="py-2 pr-4">{p.birthDate}</td>
                        <td className="py-2 pr-4">{p.administrativeSex}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>

        <div className="flex items-center justify-between">
          <Button
            variant="outline"
            disabled={offset === 0 || query.isFetching}
            onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
          >
            ← Previous
          </Button>
          <p className="text-muted-foreground text-xs">
            Page size {PAGE_SIZE}
          </p>
          <Button
            variant="outline"
            disabled={!page || !page.hasMore || query.isFetching}
            onClick={() => setOffset(offset + PAGE_SIZE)}
          >
            Next →
          </Button>
        </div>
      </div>
    </div>
  )
}
