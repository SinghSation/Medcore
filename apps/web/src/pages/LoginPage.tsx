import * as React from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'

import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { useAuth } from '@/providers/AuthProvider'
import { fetchMe } from '@/lib/identity'
import { ApiError } from '@/lib/api-client'
import { setToken, clearToken } from '@/lib/auth'

interface LocationState {
  from?: string
}

export function LoginPage(): React.JSX.Element {
  const { signIn } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as LocationState | null)?.from ?? '/'

  const [value, setValue] = React.useState('')

  const mutation = useMutation({
    mutationFn: async (token: string) => {
      setToken(token)
      try {
        await fetchMe()
      } catch (err) {
        clearToken()
        throw err
      }
    },
    onSuccess: () => {
      signIn(value)
      navigate(from, { replace: true })
    },
  })

  function onSubmit(event: React.FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    const token = value.trim()
    if (token.length === 0) {
      return
    }
    mutation.mutate(token)
  }

  const errorMessage = mutation.error ? explain(mutation.error) : null

  return (
    <div className="grid min-h-screen place-items-center bg-muted/30 p-4">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>Medcore sign-in</CardTitle>
          <CardDescription>
            Paste a bearer token to enter the demo. Tokens are not
            persisted — refresh clears the session.
          </CardDescription>
        </CardHeader>
        <form onSubmit={onSubmit}>
          <CardContent className="flex flex-col gap-3">
            <Label htmlFor="token">Bearer token</Label>
            <textarea
              id="token"
              name="token"
              autoComplete="off"
              spellCheck={false}
              value={value}
              onChange={(e) => setValue(e.target.value)}
              className="border-input focus-visible:border-ring focus-visible:ring-ring/50 flex min-h-[120px] w-full rounded-md border bg-transparent px-3 py-2 font-mono text-xs shadow-xs transition-[color,box-shadow] outline-none focus-visible:ring-[3px]"
              placeholder="eyJhbGciOi..."
              required
            />
            {errorMessage !== null && (
              <p
                role="alert"
                className="text-destructive text-sm"
                data-testid="login-error"
              >
                {errorMessage}
              </p>
            )}
          </CardContent>
          <CardFooter className="flex justify-end gap-2 pt-4">
            <Button
              type="submit"
              disabled={mutation.isPending || value.trim().length === 0}
            >
              {mutation.isPending ? 'Verifying…' : 'Sign in'}
            </Button>
          </CardFooter>
        </form>
      </Card>
    </div>
  )
}

function explain(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 401) return 'Token rejected (401). Paste a valid token.'
    if (err.status === 403) return 'Token accepted but access denied (403).'
    // A 5xx with no body almost always means the Vite dev proxy could
    // not reach the API — the backend envelope (ADR-007) always emits
    // a JSON body, so an empty body is diagnostic of "upstream dead"
    // in dev, not of a real backend error.
    if (err.status >= 500 && err.body === null) {
      return 'API is unreachable on :8080. Is `make api-dev` running?'
    }
    return `Request failed (${err.status}).`
  }
  return 'Unable to reach the API. Is it running on :8080?'
}
