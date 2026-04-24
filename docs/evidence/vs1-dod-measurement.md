# VS1 DoD Measurement — Phase 4D.3

**Source roadmap exits** (`docs/product/02-roadmap.md`):

| DoD | Bound | Measured in |
|---|---|---|
| Start-encounter workflow from clinician landing | **≤ 3 clicks** | `apps/web/e2e/vs1-happy-path.spec.ts` |
| Encounter-started → note-ready-to-save, simple ambulatory | **≤ 90 s** | `apps/web/e2e/vs1-happy-path.spec.ts` |

This document is refreshed whenever the happy-path spec is re-run
against a stable main. Playwright console prints:

```
[e2e-dod] clicks=<N> note_save_ms=<N> patient=TestPatient-XYZABC
```

The number recorded below is the median of five consecutive clean
runs on the author's local workstation (Darwin / Apple Silicon,
Node.js ≥ 20, Chromium headless-shell 147).

## Measurement — 2026-04-24

**Stack baseline:** commits `b1ff0c3..13007d9` on `main` (VS1
Chunks A–E). Mock OAuth2, API, Vite, Postgres all running under
their standard local-dev targets.

**Method:**

1. `docker compose up -d postgres mock-oauth2-server`
2. `set -a && source .env && set +a && make api-dev` (separate terminal)
3. `make web-dev` (separate terminal)
4. `set -a && source .env && set +a && make e2e` (×5, back-to-back)

**Results:**

| Run | Clicks | Note save (ms) | Verdict |
|---|---|---|---|
| 1 | 3 | 415 | PASS (cold start) |
| 2 | 3 | 158 | PASS |
| 3 | 3 | 176 | PASS |
| 4 | 3 | 145 | PASS |
| 5 | 3 | 148 | PASS |
| **Median** | **3** | **158** | **PASS — ~570× under 90 s ceiling** |

Zero flakes. Zero retries configured. Zero warnings in the
Playwright reporter.

**PHI leakage scan** — `apps/web/e2e/phi-leakage.spec.ts`:

| Surface | Content after full flow |
|---|---|
| `localStorage` | 0 keys |
| `sessionStorage` | 0 keys |
| `document.cookie` | 0 chars |
| `console.*` | 4 lines (React Router future-flag warnings only; no PHI) |
| Sentinels found (given / family / MRN / birth date / note body) | **0** |
| Bearer-token string leaked to storage / cookie / console | **No** |

Passed on all 5 runs.

## How to repeat

One command:

```bash
set -a && source .env && set +a
docker compose up -d postgres mock-oauth2-server
make api-dev &       # separate terminal preferred
make web-dev &       # separate terminal preferred
make e2e
```

If the DoD fails, investigate before weakening the bound. Phase
4D exit is contingent on this measurement holding.

## Why the margin is so large

The current VS1 note path is trivial: type + POST + refetch.
The 90 s Phase 4D DoD is sized for the *structured-note* workflow
that VS1 explicitly defers — templates, section navigation,
structured observations, signing gate. As those surfaces land
in Phase 4D full exit, the measurement here tightens toward
the bound. Today's generous margin is not a sign the DoD is
wrong — it's a sign VS1 is a thin slice of the eventual surface.

## Carry-forward

Items deliberately excluded from this measurement:

- **CI enforcement** — E2E gate in GitHub Actions lands in Chunk G.
- **Multi-browser matrix** — Firefox + WebKit measurements post-VS1.
- **Mobile/tablet viewports** — Phase 6C.
- **Visual regression** — design-system phase.
- **A11y (axe) audit in E2E** — design-system phase.
- **Bundled login→landing flow** — login clicks are not part of
  the Phase 4C DoD, but a separate onboarding-DoD slice may
  measure them.
- **DOM hidden-field / network-response-cache / service-worker
  PHI scans** — dedicated PHI-hardening slice after VS1.
- **Performance budgets (FCP / LCP / TBT)** — post-VS1.
