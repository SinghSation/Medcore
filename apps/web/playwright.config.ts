import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright config for VS1 Chunk F (Phase 4D.3) — end-to-end DoD
 * and PHI leakage coverage.
 *
 * ### Expectations
 *
 * - Vite dev server already running at `http://localhost:5173`
 *   (`make web-dev`).
 * - Medcore API already running at `http://localhost:8080`
 *   (`make api-dev`).
 * - Mock OAuth2 server already running at `http://localhost:8888`
 *   (`docker compose up -d mock-oauth2-server`).
 * - Postgres already running with the `.env`-configured superuser
 *   (`docker compose up -d postgres`).
 *
 * Running the Playwright suite does NOT start these services —
 * that keeps the E2E run tight and forces the developer to
 * operate the same stack clinicians will. CI wiring is a
 * separate chunk (see carry-forward in the Chunk F commit).
 *
 * ### VS1 scope discipline
 *
 * - Chromium only (multi-browser matrix deferred).
 * - One worker, non-parallel (tests share the seed tenant).
 * - No retries — flakes must be fixed, not hidden.
 * - 120 s per-test timeout is a ceiling; the 90 s note-flow DoD
 *   is asserted inside the test, not at the Playwright layer.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 120_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
  ],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  globalSetup: './e2e/global-setup.ts',
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
})
