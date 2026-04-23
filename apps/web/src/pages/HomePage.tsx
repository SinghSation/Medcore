import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { fetchMe } from '@/lib/identity'
import { fetchMyTenants } from '@/lib/tenancy'
import { useAuth } from '@/providers/AuthProvider'

export function HomePage(): React.JSX.Element {
  const { signOut } = useAuth()
  const navigate = useNavigate()

  const me = useQuery({ queryKey: ['me'], queryFn: () => fetchMe() })
  const tenants = useQuery({
    queryKey: ['tenants', 'mine'],
    queryFn: () => fetchMyTenants(),
  })

  function onSignOut(): void {
    signOut()
    navigate('/login', { replace: true })
  }

  return (
    <div className="min-h-screen bg-muted/30 p-6">
      <div className="mx-auto flex max-w-4xl flex-col gap-6">
        <header className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-semibold">Medcore</h1>
            <p className="text-muted-foreground text-sm">
              Demo shell — Vertical Slice 1
            </p>
          </div>
          <Button variant="outline" onClick={onSignOut}>
            Sign out
          </Button>
        </header>

        <Card>
          <CardHeader>
            <CardTitle>Signed in</CardTitle>
            <CardDescription>
              Result of <code>GET /api/v1/me</code>
            </CardDescription>
          </CardHeader>
          <CardContent>
            {me.isLoading && <p className="text-muted-foreground text-sm">Loading…</p>}
            {me.isError && (
              <p className="text-destructive text-sm">
                Unable to load identity.
              </p>
            )}
            {me.data && (
              <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
                <dt className="text-muted-foreground">User ID</dt>
                <dd className="font-mono">{me.data.userId}</dd>
                <dt className="text-muted-foreground">Subject</dt>
                <dd className="font-mono">{me.data.subject}</dd>
                <dt className="text-muted-foreground">Issuer</dt>
                <dd className="font-mono break-all">{me.data.issuer}</dd>
                <dt className="text-muted-foreground">Status</dt>
                <dd>{me.data.status}</dd>
              </dl>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Your tenants</CardTitle>
            <CardDescription>
              Memberships visible through <code>GET /api/v1/tenants</code>
            </CardDescription>
          </CardHeader>
          <CardContent>
            {tenants.isLoading && (
              <p className="text-muted-foreground text-sm">Loading…</p>
            )}
            {tenants.isError && (
              <p className="text-destructive text-sm">
                Unable to load tenants.
              </p>
            )}
            {tenants.data && tenants.data.length === 0 && (
              <p className="text-muted-foreground text-sm">
                No active memberships on this account.
              </p>
            )}
            {tenants.data && tenants.data.length > 0 && (
              <ul className="flex flex-col gap-2">
                {tenants.data.map((m) => (
                  <li key={m.membershipId}>
                    <Link
                      to={`/tenants/${encodeURIComponent(m.tenant.slug)}/patients`}
                      className="hover:bg-accent/40 flex items-center justify-between rounded-md border p-3 transition-colors"
                    >
                      <div>
                        <div className="font-medium">{m.tenant.displayName}</div>
                        <div className="text-muted-foreground font-mono text-xs">
                          {m.tenant.slug}
                        </div>
                      </div>
                      <span className="text-muted-foreground text-xs">
                        {m.role} · view patients →
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
