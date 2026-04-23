import * as React from 'react'
import { Navigate, useLocation } from 'react-router-dom'

import { useAuth } from '@/providers/AuthProvider'

export function ProtectedRoute({
  children,
}: {
  children: React.ReactNode
}): React.JSX.Element {
  const { hasToken } = useAuth()
  const location = useLocation()

  if (!hasToken) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return <>{children}</>
}
