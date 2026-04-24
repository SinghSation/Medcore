/**
 * VS1 happy-path — DoD gate.
 *
 * Covers the full clinician loop end-to-end through a real
 * browser: login → home → patient list → patient detail → start
 * encounter → write note → save note → see note in list.
 *
 * ### Assertions
 *
 * 1. **≤ 3 clicks** from home (post-login landing) to
 *    "encounter started" (encounter detail card visible).
 *    Source: Phase 4C roadmap exit — `docs/product/02-roadmap.md`.
 *    Caveat: a dedicated "today's schedule" view does not yet
 *    exist in VS1; home (`/`) is the nearest analogue and what
 *    VS1 uses as the clinician landing.
 *
 * 2. **≤ 90 seconds** from encounter-started to note-saved.
 *    Source: Phase 4D roadmap exit — same doc.
 *
 * The wall-clock is recorded in `docs/evidence/vs1-dod-measurement.md`
 * each time this spec runs clean. If the assertion fires, investigate
 * before weakening the bound.
 */

import { expect, test } from '@playwright/test'

import { loginViaUi } from './fixtures/auth'
import {
  E2E_PATIENT_MRN,
  E2E_PATIENT_NAME_FAMILY,
  E2E_TENANT_SLUG,
  resetEncountersForE2eTenant,
} from './fixtures/seed'

const DOD_MAX_CLICKS_TO_ENCOUNTER = 3
const DOD_MAX_MS_TO_NOTE_SAVED = 90_000

function requireEnv(name: string): string {
  const v = process.env[name]
  if (!v) {
    throw new Error(
      `Missing env ${name} — global-setup.ts must run before specs`,
    )
  }
  return v
}

test.describe('VS1 happy path — DoD', () => {
  // Phase 4C.4: per-patient IN_PROGRESS is unique (V22). The
  // sibling phi-leakage spec opens an encounter on the same
  // seeded patient; without this reset we'd land on the detail
  // page and see Resume instead of Start.
  test.beforeEach(async () => {
    await resetEncountersForE2eTenant()
  })

  test('login → encounter started → note saved, inside both DoDs', async ({
    page,
  }) => {
    const token = requireEnv('E2E_TOKEN')

    // --- Authenticate (NOT counted against the ≤3-click DoD —
    // the DoD measures the clinician flow starting from the
    // landing view, per Phase 4C exit phrasing).
    await loginViaUi(page, token)

    // Post-login landing is HomePage at `/` — it lists tenants.
    await page.waitForURL('**/', { timeout: 10_000 })

    let clicks = 0

    // --- Click 1: select tenant → patient list.
    await page
      .getByRole('link', {
        name: new RegExp(`${E2E_TENANT_SLUG}`, 'i'),
      })
      .click()
    clicks += 1
    await expect(page.getByTestId('patient-list-card')).toBeVisible()

    // --- Click 2: select patient row → patient detail.
    // The MRN cell is a `<Link>` that navigates on click. It's
    // the deterministic identifier in the table — family name is
    // rendered as plain text and would require hit-testing the
    // row, which is fine to wire later.
    await page.getByRole('link', { name: E2E_PATIENT_MRN }).click()
    clicks += 1
    await expect(page.getByTestId('patient-detail-card')).toBeVisible()

    // --- Click 3: Start encounter → encounter detail.
    await page.getByTestId('start-encounter-button').click()
    clicks += 1
    await expect(page.getByTestId('encounter-detail-card')).toBeVisible()

    expect(clicks).toBeLessThanOrEqual(DOD_MAX_CLICKS_TO_ENCOUNTER)

    // --- Now start the 90s clock. The spec.
    // Start boundary: encounter detail card is up, textarea is ready.
    // Stop boundary: the note body text appears in the notes list.
    const noteBody = `e2e-note-${Date.now()} chief complaint stable`
    await expect(page.getByTestId('note-body-textarea')).toBeVisible()

    const start = performance.now()
    await page.getByTestId('note-body-textarea').fill(noteBody)
    await page.getByTestId('save-note-button').click()
    const savedNote = page
      .getByTestId('notes-list')
      .getByText(noteBody, { exact: true })
    await expect(savedNote).toBeVisible({ timeout: DOD_MAX_MS_TO_NOTE_SAVED })
    const elapsedMs = performance.now() - start

    expect(elapsedMs).toBeLessThanOrEqual(DOD_MAX_MS_TO_NOTE_SAVED)

    // Textarea clears on successful save (Chunk E contract).
    await expect(page.getByTestId('note-body-textarea')).toHaveValue('')

    // Surface the measured numbers for the evidence doc.
    // eslint-disable-next-line no-console
    console.log(
      `[e2e-dod] clicks=${clicks} note_save_ms=${Math.round(elapsedMs)} ` +
        `patient=${E2E_PATIENT_NAME_FAMILY}`,
    )
  })
})
