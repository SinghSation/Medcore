/**
 * Phase 4E.2 — Problems chart-context end-to-end spec.
 *
 * Walks the chart-context surface in a real browser:
 *
 *   login → patient → empty Problems card → open Manage →
 *   add Hypertension → see card populate → mark resolved →
 *   card empties (ACTIVE-only filter) → modal still shows
 *   the row labelled "Resolved" → confirm RESOLVED ≠ INACTIVE
 *   structurally (no "Mark inactive" button on a RESOLVED
 *   row; the only post-RESOLVED transitions exposed are
 *   "Mark active (recurrence)" + "Mark entered in error")
 *
 * **Patient page only** (locked Q6 — distinct from allergies
 * which mount on both patient + encounter pages). The encounter
 * detail page should NOT carry a problems card; the spec does
 * not navigate there for problems.
 *
 * **RESOLVED ≠ INACTIVE** — this spec exercises the load-bearing
 * UI invariant: the RESOLVED state has its own visual treatment,
 * its own audit slug at the API layer, AND its own set of legal
 * UI controls. The "Mark inactive" button is structurally absent
 * on RESOLVED rows.
 *
 * Lives as its own spec (not appended to vs1-happy-path or
 * allergy.spec) so existing DoD bounds stay tight.
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

test.describe('Phase 4E.2 — problems card end-to-end', () => {
  // resetEncountersForE2eTenant also clears clinical.problem as
  // of 4E.2 chunk F so a problem added by an earlier run does
  // not leak into the next assertion of "empty card first."
  test.beforeEach(async () => {
    await resetEncountersForE2eTenant()
  })

  test('add → resolve → RESOLVED ≠ INACTIVE structural enforcement', async ({
    page,
  }) => {
    const token = requireEnv('E2E_TOKEN')
    const stamp = Date.now()
    const condition = `e2e-condition-${stamp}` // distinctive sentinel

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

    // --- Card is mounted and starts empty.
    const card = page.getByTestId('problems-card')
    await expect(card).toBeVisible()
    await expect(card.getByTestId('problems-card-empty')).toBeVisible()
    await expect(card.getByTestId('problems-card-list')).toHaveCount(0)

    // --- Open Manage modal, fill the add form.
    await card.getByTestId('problem-manage-button').click()
    const modal = page.getByTestId('problem-manage-modal')
    await expect(modal).toBeVisible()

    await modal.getByTestId('problem-condition-input').fill(condition)
    // Severity left at default "Unspecified" — locked Q3 nullable.
    await modal.getByTestId('problem-add-button').click()

    // --- After mutation, the card repopulates with the new
    //     ACTIVE row.
    await expect(card.getByTestId('problems-card-list')).toBeVisible({
      timeout: 30_000,
    })
    const cardRow = card.locator('[data-testid="problem-card-row"]')
    await expect(cardRow).toHaveCount(1)
    await expect(cardRow).toHaveAttribute('data-problem-status', 'ACTIVE')
    await expect(cardRow).toContainText(condition)

    // --- Mark resolved. Card must clear (ACTIVE-only filter);
    //     modal must still show the row, now labelled "Resolved".
    const manageRow = modal
      .locator('[data-testid="problem-manage-row"]')
      .filter({ hasText: condition })
    await manageRow.getByTestId('problem-resolve-button').click()

    // After PATCH succeeds + refetch, card empty state returns.
    await expect(card.getByTestId('problems-card-empty')).toBeVisible({
      timeout: 30_000,
    })

    // The same row inside the modal is now labelled RESOLVED —
    // distinct from INACTIVE (load-bearing RESOLVED ≠ INACTIVE).
    const resolvedRow = modal
      .locator('[data-testid="problem-manage-row"]')
      .filter({ hasText: condition })
    await expect(resolvedRow).toHaveAttribute(
      'data-problem-status',
      'RESOLVED',
    )
    await expect(
      resolvedRow.getByTestId('problem-manage-status'),
    ).toHaveText('Resolved')

    // --- RESOLVED ≠ INACTIVE structural UI enforcement.
    //     The RESOLVED row offers exactly two transition controls:
    //       * Mark active (recurrence) → routes to UPDATED audit
    //         with status_from:RESOLVED|status_to:ACTIVE
    //       * Mark entered in error → routes to REVOKED audit
    //     It does NOT offer "Mark inactive" — RESOLVED → INACTIVE
    //     would 409 with problem_invalid_transition; the UI
    //     never even gives the user the option.
    await expect(
      resolvedRow.getByTestId('problem-recurrence-button'),
    ).toBeVisible()
    await expect(
      resolvedRow.getByTestId('problem-revoke-button'),
    ).toBeVisible()
    await expect(
      resolvedRow.getByTestId('problem-deactivate-button'),
    ).toHaveCount(0)
    await expect(
      resolvedRow.getByTestId('problem-resolve-button'),
    ).toHaveCount(0)

    // --- Mark active (recurrence): RESOLVED → ACTIVE. The card
    //     repopulates because the row is ACTIVE again.
    await resolvedRow.getByTestId('problem-recurrence-button').click()
    await expect(card.getByTestId('problems-card-list')).toBeVisible({
      timeout: 30_000,
    })
    const recurredRow = card
      .locator('[data-testid="problem-card-row"]')
      .filter({ hasText: condition })
    await expect(recurredRow).toHaveAttribute('data-problem-status', 'ACTIVE')

    // Close the modal cleanly.
    await modal.getByTestId('problem-manage-close').click()
    await expect(modal).toHaveCount(0)
  })
})
