/**
 * Phase 4D.6 — Note amendment end-to-end spec.
 *
 * Walks the full clinical-correction loop in a real browser:
 *
 *   login → patient → start encounter → write+save note →
 *   sign note → finish encounter → amend the signed note →
 *   sign the amendment → verify threading + Amended badge +
 *   encounter still FINISHED.
 *
 * The post-finish amend → sign step is the cross-slice proof
 * for chunk B.5 (the closed-encounter carve-out): an amendment
 * on a FINISHED encounter MUST be signable. Without that fix,
 * the amendment would stick at DRAFT and this spec would hang
 * waiting for the SIGNED badge on the amendment row.
 *
 * ### Why this is a separate spec (not added to vs1-happy-path)
 *
 * vs1-happy-path tracks two strict DoD bounds (≤3 clicks and
 * ≤90s to first note saved). The amend flow is longer and is
 * not part of those bounds — keeping it in its own spec means
 * the DoD spec stays tight while we still get full-stack proof
 * of the amendment workflow on every CI run.
 */

import { expect, test } from '@playwright/test'

import { loginViaUi } from './fixtures/auth'
import {
  E2E_PATIENT_MRN,
  E2E_TENANT_SLUG,
  resetEncountersForE2eTenant,
} from './fixtures/seed'

function requireEnv(name: string): string {
  const v = process.env[name]
  if (!v) {
    throw new Error(
      `Missing env ${name} — global-setup.ts must run before specs`,
    )
  }
  return v
}

test.describe('Phase 4D.6 — note amendment end-to-end', () => {
  // Per-spec encounter reset (added in Phase 4C.4): the seeded
  // patient must start with no IN_PROGRESS encounter, otherwise
  // the patient detail page would render Resume instead of Start
  // and this spec's `start-encounter-button` would not be found.
  test.beforeEach(async () => {
    await resetEncountersForE2eTenant()
  })

  test('amend a signed note on a FINISHED encounter, then sign the amendment', async ({
    page,
  }) => {
    const token = requireEnv('E2E_TOKEN')

    // Distinctive sentinels so we can identify the right rows in
    // the notes list later. Time-suffixed to keep them unique
    // across CI runs in the unlikely event of cleanup partial.
    const stamp = Date.now()
    const originalBody = `e2e-orig-${stamp} chief complaint stable`
    const amendmentBody = `e2e-amend-${stamp} dosage corrected to 5 mg`

    // --- Authenticate + navigate to the encounter detail page.
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

    // --- Write + save the original note.
    await page.getByTestId('note-body-textarea').fill(originalBody)
    await page.getByTestId('save-note-button').click()
    const originalRow = page
      .getByTestId('notes-list')
      .locator('[data-testid="note-row"]')
      .filter({ hasText: originalBody })
    await expect(originalRow).toBeVisible({ timeout: 30_000 })
    await expect(originalRow).toHaveAttribute('data-note-status', 'DRAFT')

    // --- Sign the original note.
    await originalRow.getByTestId('sign-note-button').click()
    await expect(originalRow).toHaveAttribute('data-note-status', 'SIGNED', {
      timeout: 30_000,
    })

    // --- Finish the encounter (precondition: at least one signed
    //     note exists, satisfied by the step above).
    await page.getByTestId('finish-encounter-button').click()
    // Encounter detail card flips to FINISHED. Status text appears
    // in multiple places (CardTitle + Status row); we check at
    // least one. The lifecycle-actions block unmounts when the
    // encounter is no longer IN_PROGRESS.
    await expect(
      page.getByTestId('encounter-lifecycle-actions'),
    ).toHaveCount(0, { timeout: 30_000 })
    await expect(page.getByTestId('encounter-detail-card')).toContainText(
      'FINISHED',
    )

    // --- Amend the signed original note. The Amend button must
    //     remain visible on a SIGNED non-amendment note even when
    //     the encounter is closed (locked plan: closed-encounter
    //     amend is the entire point of 4D.6).
    await originalRow.getByTestId('amend-note-button').click()
    const amendEditor = originalRow.getByTestId('amend-note-editor')
    await expect(amendEditor).toBeVisible()
    // Editor pre-fills with the original body for context; replace
    // it with the corrected text.
    const amendTextarea = amendEditor.getByTestId('amend-note-textarea')
    await expect(amendTextarea).toHaveValue(originalBody)
    await amendTextarea.fill(amendmentBody)
    await amendEditor.getByTestId('amend-save-button').click()

    // --- Wait for the amendment to appear threaded under the
    //     original, marked as DRAFT, with the amendment-status
    //     badge.
    const thread = originalRow.getByTestId('note-amendments-thread')
    const amendmentRow = thread
      .locator('[data-testid="note-row"]')
      .filter({ hasText: amendmentBody })
    await expect(amendmentRow).toBeVisible({ timeout: 30_000 })
    await expect(amendmentRow).toHaveAttribute(
      'data-note-is-amendment',
      'true',
    )
    await expect(amendmentRow).toHaveAttribute('data-note-status', 'DRAFT')

    // --- "Amended" badge must now appear on the original.
    await expect(
      originalRow.getByTestId('note-amended-badge').first(),
    ).toBeVisible()

    // --- Sign the amendment. This is the chunk B.5 carve-out:
    //     a draft amendment on a FINISHED encounter MUST be
    //     signable. Without that fix, this click would surface
    //     409 encounter_closed and the row would never flip to
    //     SIGNED.
    await amendmentRow.getByTestId('sign-note-button').click()
    await expect(amendmentRow).toHaveAttribute('data-note-status', 'SIGNED', {
      timeout: 30_000,
    })

    // --- Encounter status must NOT have reopened. Signing an
    //     amendment does not reset the encounter lifecycle.
    await expect(page.getByTestId('encounter-detail-card')).toContainText(
      'FINISHED',
    )
    await expect(
      page.getByTestId('encounter-lifecycle-actions'),
    ).toHaveCount(0)
  })
})
