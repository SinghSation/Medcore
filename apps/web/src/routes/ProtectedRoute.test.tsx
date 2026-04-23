import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it } from 'vitest'

import { AuthProvider } from '@/providers/AuthProvider'
import { ProtectedRoute } from '@/routes/ProtectedRoute'
import { clearToken, setToken } from '@/lib/auth'

describe('ProtectedRoute', () => {
  afterEach(() => {
    clearToken()
  })

  it('redirects to /login when there is no token', () => {
    render(
      <MemoryRouter initialEntries={['/private']}>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<div>Login page</div>} />
            <Route
              path="/private"
              element={
                <ProtectedRoute>
                  <div>Private content</div>
                </ProtectedRoute>
              }
            />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    )

    expect(screen.getByText('Login page')).toBeInTheDocument()
    expect(screen.queryByText('Private content')).not.toBeInTheDocument()
  })

  it('renders children when a token is set', () => {
    setToken('abc')

    render(
      <MemoryRouter initialEntries={['/private']}>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<div>Login page</div>} />
            <Route
              path="/private"
              element={
                <ProtectedRoute>
                  <div>Private content</div>
                </ProtectedRoute>
              }
            />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    )

    expect(screen.getByText('Private content')).toBeInTheDocument()
  })
})
