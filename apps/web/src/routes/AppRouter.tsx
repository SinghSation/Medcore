import { createBrowserRouter, RouterProvider } from 'react-router-dom'

import { ProtectedRoute } from '@/routes/ProtectedRoute'
import { LoginPage } from '@/pages/LoginPage'
import { HomePage } from '@/pages/HomePage'
import { PatientListPage } from '@/pages/PatientListPage'
import { PatientDetailPage } from '@/pages/PatientDetailPage'
import { EncounterDetailPage } from '@/pages/EncounterDetailPage'

const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/',
    element: (
      <ProtectedRoute>
        <HomePage />
      </ProtectedRoute>
    ),
  },
  {
    path: '/tenants/:slug/patients',
    element: (
      <ProtectedRoute>
        <PatientListPage />
      </ProtectedRoute>
    ),
  },
  {
    path: '/tenants/:slug/patients/:patientId',
    element: (
      <ProtectedRoute>
        <PatientDetailPage />
      </ProtectedRoute>
    ),
  },
  {
    path: '/tenants/:slug/encounters/:encounterId',
    element: (
      <ProtectedRoute>
        <EncounterDetailPage />
      </ProtectedRoute>
    ),
  },
  {
    path: '*',
    element: (
      <ProtectedRoute>
        <HomePage />
      </ProtectedRoute>
    ),
  },
])

export function AppRouter(): React.JSX.Element {
  return <RouterProvider router={router} />
}
