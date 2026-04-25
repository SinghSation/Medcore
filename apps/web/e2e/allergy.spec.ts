/**
 * Phase 4E.1 — Allergy banner end-to-end spec.
 *
 * Walks the clinical-safety surface in a real browser:
 *
 *   login → patient → empty allergy banner → open Manage →
 *   add Penicillin/SEVERE → see banner populate → start
 *   encounter → encounter detail also shows the banner with
 *   the same allergy
 *
 * Cross-page proof: allergies are patient-level longitudinal
 * data, so adding on the patient detail page must surface on
 * every encounter view of the same patient. The encounter-page
 * assertion at the bottom is the cross-mount invariant in
 * action.
 *
 * Lives as its own spec (not appended to vs1-happy-path) so
 * the existing DoD bounds stay tight; banner-add is not part
 * of those bounds.
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

test.describe('Phase 4E.1 — allergy banner end-to-end', () => {
  // resetEncountersForE2eTenant also clears clinical.allergy as
  // of 4E.1 chunk F so an allergy added by an earlier run does
  // not leak into the next assertion of "empty banner first."
  test.beforeEach(async () => {
    await resetEncountersForE2eTenant()
  })

  test('add allergy on patient page → see banner on patient + encounter', async ({
    page,
  }) => {
    const token = requireEnv('E2E_TOKEN')
    const stamp = Date.now()
    const substance = `e2e-substance-${stamp}` // distinctive sentinel

    // --- Login + navigate to patient detail.
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

    // --- Banner is mounted and starts empty.
    const banner = page.getByTestId('allergy-banner')
    await expect(banner).toBeVisible()
    await expect(banner.getByTestId('allergy-banner-empty')).toBeVisible()
    await expect(banner.getByTestId('allergy-banner-list')).toHaveCount(0)

    // --- Open Manage modal, fill the add form.
    await banner.getByTestId('allergy-manage-button').click()
    const modal = page.getByTestId('allergy-manage-modal')
    await expect(modal).toBeVisible()

    await modal.getByTestId('allergy-substance-input').fill(substance)
    await modal
      .getByTestId('allergy-severity-select')
      .selectOption('SEVERE')
    // Reaction left empty — optional field per the wire contract.
    await modal.getByTestId('allergy-add-button').click()

    // --- After mutation, the banner repopulates with the new
    //     ACTIVE row. Substance text appears; severity chip
    //     reads "Severe".
    await expect(banner.getByTestId('allergy-banner-list')).toBeVisible({
      timeout: 30_000,
    })
    const bannerRow = banner.locator('[data-testid="allergy-banner-row"]')
    await expect(bannerRow).toHaveCount(1)
    await expect(bannerRow).toHaveAttribute('data-allergy-status', 'ACTIVE')
    await expect(bannerRow).toContainText(substance)
    await expect(bannerRow.getByTestId('allergy-banner-severity')).toContainText(
      'Severe',
    )

    // Close the modal so it doesn't intercept clicks below.
    await modal.getByTestId('allergy-manage-close').click()
    await expect(modal).toHaveCount(0)

    // --- Cross-mount invariant: navigate to encounter detail
    //     and confirm the SAME banner shows the SAME allergy.
    //     Start a fresh encounter (the seed has none after the
    //     beforeEach reset).
    await page.getByTestId('start-encounter-button').click()
    await expect(page.getByTestId('encounter-detail-card')).toBeVisible()
    const encounterBanner = page.getByTestId('allergy-banner')
    await expect(encounterBanner).toBeVisible()
    const encounterBannerRow = encounterBanner.locator(
      '[data-testid="allergy-banner-row"]',
    )
    await expect(encounterBannerRow).toHaveCount(1)
    await expect(encounterBannerRow).toContainText(substance)

    // --- Status transition: mark inactive on encounter page.
    //     The banner must clear (ACTIVE-only filter).
    await encounterBanner.getByTestId('allergy-manage-button').click()
    const encounterModal = page.getByTestId('allergy-manage-modal')
    await expect(encounterModal).toBeVisible()
    await encounterModal.getByTestId('allergy-deactivate-button').click()

    // After PATCH succeeds + refetch, banner shows the empty
    // state (the row is INACTIVE now and filtered out of the
    // banner). The row stays visible inside the management
    // modal as "Inactive" — proves the banner-vs-management
    // separation.
    await expect(
      encounterBanner.getByTestId('allergy-banner-empty'),
    ).toBeVisible({ timeout: 30_000 })
    const inactiveRowInModal = encounterModal
      .locator('[data-testid="allergy-manage-row"]')
      .filter({ hasText: substance })
    await expect(inactiveRowInModal).toHaveAttribute(
      'data-allergy-status',
      'INACTIVE',
    )
  })
})
