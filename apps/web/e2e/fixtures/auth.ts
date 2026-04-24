/**
 * Token + login helpers for Playwright E2E (VS1 Chunk F).
 *
 * The real clinician flow is: paste a bearer token into the
 * `/login` page. That's what these helpers exercise — NOT a
 * programmatic-storage shortcut. Keeping the browser path in
 * the loop is the whole point of E2E for this slice.
 */

import type { Page } from '@playwright/test'

export const E2E_ISSUER = 'http://localhost:8888/default'
export const E2E_TOKEN_ENDPOINT = `${E2E_ISSUER}/token`
export const E2E_CLIENT_ID = 'medcore-dev'
export const E2E_CLIENT_SECRET = 'secret'
export const E2E_SUBJECT = 'demo-user-1'

/**
 * Mints a fresh bearer token for {@link E2E_SUBJECT} against the
 * mock-oauth2-server running in docker-compose. Uses the OIDC
 * `password` grant — not because we care about password flow,
 * but because it's the one grant that lets us pin the token's
 * `sub` claim to a deterministic value from outside the browser.
 *
 * The mock server accepts any password when password grant is
 * enabled; there is no real secret here.
 */
export async function mintToken(subject: string = E2E_SUBJECT): Promise<string> {
  const body = new URLSearchParams({
    grant_type: 'password',
    username: subject,
    password: 'x',
    scope: 'openid',
  })
  const response = await fetch(E2E_TOKEN_ENDPOINT, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      Authorization:
        'Basic ' +
        Buffer.from(`${E2E_CLIENT_ID}:${E2E_CLIENT_SECRET}`).toString('base64'),
    },
    body,
  })
  if (!response.ok) {
    throw new Error(
      `mock-oauth2 token mint failed: ${response.status} ${await response.text()}`,
    )
  }
  const json = (await response.json()) as { access_token?: string }
  if (!json.access_token) {
    throw new Error('mock-oauth2 response missing access_token')
  }
  return json.access_token
}

/**
 * Drives the `/login` page to completion. Pastes the given token
 * into the form and clicks Sign in. Leaves the page on whatever
 * route the app redirects to (patient list, typically).
 */
export async function loginViaUi(page: Page, token: string): Promise<void> {
  await page.goto('/login')
  await page.getByTestId('token-input').fill(token)
  await page.getByTestId('sign-in-button').click()
  await page.waitForURL((url) => !url.pathname.endsWith('/login'), {
    timeout: 15_000,
  })
}

/**
 * Hit `/api/v1/me` with the token to trigger JIT user
 * provisioning server-side and fetch the resulting user id.
 * The E2E seeder needs this id to create a tenant membership
 * for the demo user.
 */
export async function provisionAndFetchUserId(token: string): Promise<string> {
  const response = await fetch('http://localhost:8080/api/v1/me', {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (!response.ok) {
    throw new Error(
      `GET /api/v1/me failed: ${response.status} ${await response.text()}`,
    )
  }
  const json = (await response.json()) as { userId?: string }
  if (!json.userId) {
    throw new Error('/api/v1/me response missing userId')
  }
  return json.userId
}
