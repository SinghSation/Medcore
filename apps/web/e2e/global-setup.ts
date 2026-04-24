/**
 * Playwright global setup (runs once before the suite).
 *
 * Responsibilities:
 *   1. Preflight the stack — fail fast with actionable messages
 *      if Vite / API / mock-OAuth2 / Postgres aren't reachable.
 *   2. Mint a token for the demo subject against mock-OAuth2.
 *   3. Hit `/api/v1/me` to JIT-provision the user and get the
 *      server-assigned userId.
 *   4. Seed a deterministic tenant + OWNER membership + patient
 *      under {@link E2E_TENANT_SLUG}.
 *   5. Stash the seeded state + fresh token in env vars so specs
 *      can read them without re-running setup.
 */

import {
  E2E_ISSUER,
  E2E_SUBJECT,
  mintToken,
  provisionAndFetchUserId,
} from './fixtures/auth'
import { E2E_TENANT_SLUG, seedE2eBaseline } from './fixtures/seed'

const API_BASE = process.env.E2E_API_BASE ?? 'http://localhost:8080'
const WEB_BASE = process.env.E2E_WEB_BASE ?? 'http://localhost:5173'

export default async function globalSetup(): Promise<void> {
  await preflight()

  const token = await mintToken(E2E_SUBJECT)
  const userId = await provisionAndFetchUserId(token)
  const seeded = await seedE2eBaseline(userId)

  process.env.E2E_TOKEN = token
  process.env.E2E_USER_ID = seeded.userId
  process.env.E2E_TENANT_ID = seeded.tenantId
  process.env.E2E_TENANT_SLUG = seeded.tenantSlug
  process.env.E2E_PATIENT_ID = seeded.patientId

  // eslint-disable-next-line no-console
  console.log(
    `[e2e-setup] seeded tenant ${E2E_TENANT_SLUG} ` +
      `(id=${seeded.tenantId}), patient ${seeded.patientId}, ` +
      `user ${seeded.userId}`,
  )
}

async function preflight(): Promise<void> {
  const checks: Array<{ name: string; url: string; expected: number[] }> = [
    { name: 'Vite', url: WEB_BASE, expected: [200, 304] },
    { name: 'API health', url: `${API_BASE}/actuator/health`, expected: [200] },
    {
      name: 'Mock OAuth2 discovery',
      url: `${E2E_ISSUER}/.well-known/openid-configuration`,
      expected: [200],
    },
  ]
  for (const c of checks) {
    let status: number
    try {
      const response = await fetch(c.url)
      status = response.status
    } catch (err) {
      throw new Error(
        `[e2e-setup] preflight failed: ${c.name} (${c.url}) not reachable — ${
          (err as Error).message
        }`,
      )
    }
    if (!c.expected.includes(status)) {
      throw new Error(
        `[e2e-setup] preflight failed: ${c.name} (${c.url}) returned ${status}`,
      )
    }
  }
}
