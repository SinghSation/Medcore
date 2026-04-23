import * as React from 'react'

import { clearToken, getToken, setToken } from '@/lib/auth'

interface AuthState {
  hasToken: boolean
  signIn: (token: string) => void
  signOut: () => void
}

const AuthContext = React.createContext<AuthState | null>(null)

export function AuthProvider({
  children,
}: {
  children: React.ReactNode
}): React.JSX.Element {
  const [hasToken, setHasToken] = React.useState<boolean>(() => getToken() !== null)

  const signIn = React.useCallback((token: string) => {
    setToken(token)
    setHasToken(true)
  }, [])

  const signOut = React.useCallback(() => {
    clearToken()
    setHasToken(false)
  }, [])

  const value = React.useMemo<AuthState>(
    () => ({ hasToken, signIn, signOut }),
    [hasToken, signIn, signOut],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = React.useContext(AuthContext)
  if (ctx === null) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return ctx
}
