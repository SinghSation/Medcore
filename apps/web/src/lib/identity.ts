import { apiFetch } from '@/lib/api-client'

export type MeStatus = 'ACTIVE' | 'DISABLED' | 'DELETED'

export interface Me {
  userId: string
  issuer: string
  subject: string
  email: string | null
  emailVerified: boolean
  displayName: string | null
  preferredUsername: string | null
  status: MeStatus
}

export function fetchMe(signal?: AbortSignal): Promise<Me> {
  return apiFetch<Me>('/api/v1/me', signal !== undefined ? { signal } : {})
}
