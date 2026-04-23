import * as React from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        gcTime: 5 * 60_000,
        retry: 1,
        refetchOnWindowFocus: false,
      },
      mutations: {
        retry: 0,
      },
    },
  })
}

interface Props {
  children: React.ReactNode
  client?: QueryClient
}

export function QueryProvider({ children, client }: Props): React.JSX.Element {
  const [queryClient] = React.useState<QueryClient>(() => client ?? makeClient())
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}
