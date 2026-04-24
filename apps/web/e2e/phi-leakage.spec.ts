/**
 * VS1 frontend PHI leakage scan.
 *
 * Runs the full clinician happy-path (login → encounter → note)
 * while collecting every `console.*` message the page produces,
 * then asserts that NONE of the distinctive PHI-like sentinels
 * appear in:
 *
 *   - `localStorage`
 *   - `sessionStorage`
 *   - `document.cookie`
 *   - the captured `console.*` stream
 *
 * Sentinels scanned (all deliberately distinctive so a false
 * positive is impossible):
 *
 *   - patient given name   (`Playwright`)
 *   - patient family name  (`TestPatient-XYZABC`)
 *   - patient MRN          (`E2E-0000001`)
 *   - patient birth date   (`1985-06-15`)
 *   - note body sentinel   (unique per run)
 *
 * ### Scope discipline (Chunk F carry-forward)
 *
 * Network-response body caching inspection, service-worker cache,
 * DOM hidden-fields, and Playwright trace content are explicitly
 * OUT of Chunk F — those land in a dedicated PHI-hardening slice.
 * The storage + cookie + console triad is the minimum viable
 * enterprise-security-review baseline.
 */

import { expect, test } from '@playwright/test'

import { loginViaUi } from './fixtures/auth'
import {
  E2E_PATIENT_BIRTH_DATE,
  E2E_PATIENT_MRN,
  E2E_PATIENT_NAME_FAMILY,
  E2E_PATIENT_NAME_GIVEN,
  E2E_TENANT_SLUG,
} from './fixtures/seed'

function requireEnv(name: string): string {
  const v = process.env[name]
  if (!v) {
    throw new Error(`Missing env ${name} — global-setup.ts must run first`)
  }
  return v
}

test.describe('Frontend PHI leakage — storage + cookies + console', () => {
  test('no PHI sentinel appears in localStorage / sessionStorage / cookies / console', async ({
    page,
  }) => {
    const token = requireEnv('E2E_TOKEN')

    const consoleLines: string[] = []
    page.on('console', (msg) => {
      consoleLines.push(`[${msg.type()}] ${msg.text()}`)
    })

    const noteBody = `phi-scan-sentinel-${Date.now()} SOAP assessment`

    // --- Full happy path so the scan covers the same PHI surfaces
    // a real user would have hit.
    await loginViaUi(page, token)
    await page.waitForURL('**/', { timeout: 10_000 })
    await page
      .getByRole('link', {
        name: new RegExp(`${E2E_TENANT_SLUG}`, 'i'),
      })
      .click()
    await expect(page.getByTestId('patient-list-card')).toBeVisible()
    await page.getByRole('link', { name: E2E_PATIENT_MRN }).click()
    await expect(page.getByTestId('patient-detail-card')).toBeVisible()
    await page.getByTestId('start-encounter-button').click()
    await expect(page.getByTestId('encounter-detail-card')).toBeVisible()
    await page.getByTestId('note-body-textarea').fill(noteBody)
    await page.getByTestId('save-note-button').click()
    await expect(
      page.getByTestId('notes-list').getByText(noteBody, { exact: true }),
    ).toBeVisible({ timeout: 60_000 })

    // --- Collect state from the browser context.
    const storage = await page.evaluate(() => {
      function dump(store: Storage): Record<string, string> {
        const out: Record<string, string> = {}
        for (let i = 0; i < store.length; i += 1) {
          const k = store.key(i) as string
          out[k] = store.getItem(k) ?? ''
        }
        return out
      }
      return {
        local: dump(localStorage),
        session: dump(sessionStorage),
        cookie: document.cookie,
      }
    })

    const haystack = [
      JSON.stringify(storage.local),
      JSON.stringify(storage.session),
      storage.cookie,
      consoleLines.join('\n'),
    ].join('\n')

    const sentinels: Array<[string, string]> = [
      ['patient given name', E2E_PATIENT_NAME_GIVEN],
      ['patient family name', E2E_PATIENT_NAME_FAMILY],
      ['patient MRN', E2E_PATIENT_MRN],
      ['patient birth date', E2E_PATIENT_BIRTH_DATE],
      ['note body', noteBody],
    ]

    for (const [label, value] of sentinels) {
      expect(
        haystack.includes(value),
        `PHI leak: ${label} ("${value}") appeared in storage/cookie/console`,
      ).toBe(false)
    }

    // --- Also assert positive discipline: the token must NOT be
    // persisted to storage. AuthProvider keeps it in memory.
    expect(
      haystack.toLowerCase().includes('bearer '),
      'auth bearer string appeared in storage/cookie/console',
    ).toBe(false)

    // eslint-disable-next-line no-console
    console.log(
      `[e2e-phi-scan] storage_keys=${Object.keys(storage.local).length + Object.keys(storage.session).length}` +
        ` cookies=${storage.cookie.length} console_lines=${consoleLines.length}`,
    )
  })
})
