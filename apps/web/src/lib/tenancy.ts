import { apiFetch } from '@/lib/api-client'

export type TenantStatus = 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED'
export type MembershipStatus = 'ACTIVE' | 'SUSPENDED' | 'REVOKED'
export type MembershipRole = 'OWNER' | 'ADMIN' | 'MEMBER'

export interface TenantSummary {
  id: string
  slug: string
  displayName: string
  status: TenantStatus
}

export interface Membership {
  membershipId: string
  userId: string
  role: MembershipRole
  status: MembershipStatus
  tenant: TenantSummary
}

interface MembershipListEnvelope {
  items: Membership[]
}

export async function fetchMyTenants(
  signal?: AbortSignal,
): Promise<Membership[]> {
  const envelope = await apiFetch<MembershipListEnvelope>(
    '/api/v1/tenants',
    signal !== undefined ? { signal } : {},
  )
  return envelope.items
}
