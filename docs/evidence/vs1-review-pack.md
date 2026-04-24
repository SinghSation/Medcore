# VS1 — First clinician vertical slice (review pack)

**Closed:** 2026-04-24 (VS1 Chunk G)

**Scope:** the first end-to-end slice through every layer of the
platform that executes the complete clinician loop —
**login → patient → start encounter → write note → save** — with
auth, tenancy, PHI RLS, write-gate, audit, read-gate, and UI all
exercised in a real browser.

**What VS1 is NOT:** a phase exit. VS1 draws a thin line across
several in-progress phases (3K, 4A, 4B, 4C, 4D) to prove the
whole stack works together. Each of those phases has additional
exit criteria that VS1 deliberately defers. See `docs/product/02-roadmap.md`.

---

## Chunks + commits

| Chunk | Roadmap | Commit(s) | Summary |
|---|---|---|---|
| A | VS1-A | `6857759` | React shell + shadcn + paste-token demo login |
| B | 4B.1 + VS1-B | `5ce4e7d` + `d1e381f` | Patient list backend + UI (first PHI render) |
| Governance remediation | §3.7 | `da414b3` + `12ee0c5` + `6d12995` | Rule 07 + incident note + runbook pin |
| C | VS1-C | `28a6817` | Patient detail page + `data-phi` tagging |
| D | 4C.1 + 4C.2 | `75e6ac2` + `b1ff0c3` | Encounter shell + start encounter UI |
| E | 4D.1 + 4D.2 | `c45e16a` + `13007d9` | Encounter notes — schema + stack + UI |
| F | 4D.3 | `1dc34ef` | Playwright E2E + DoD + PHI-leakage scan |
| G | 4D.4 | *(this commit)* | CI `web` + `e2e` jobs + VS1 close-out governance |

All pushed to `origin/main`. No commits rewritten (Option B-light
applied at governance remediation per user disposition).

---

## Evidence

| Artifact | Path | Owner |
|---|---|---|
| DoD measurement (≤ 3 clicks, ≤ 90 s) — 5-run median | `docs/evidence/vs1-dod-measurement.md` | Refreshed on every clean `make e2e` |
| Chunks A/B tier-trailer skip incident | `docs/evidence/incident-2026-04-23-chunk-a-b-tier-trailer-skip.md` | Historical |
| Clinical write pattern v1.3 | `docs/evidence/clinical-write-pattern.md` | Normative |
| Project timeline (chronological narrative) | `docs/project-timeline.md` — `## VS1` section | Append-only |
| Review pack (this doc) | `docs/evidence/vs1-review-pack.md` | VS1-level summary |

---

## Gates — state at VS1 close

Every gate below is green on `main` at Chunk G landing.

| Gate | Command | Result |
|---|---|---|
| Backend tests | `./gradlew test` in `apps/api` | green |
| Frontend typecheck | `pnpm typecheck` in `apps/web` | clean |
| Frontend unit/component | `pnpm test` in `apps/web` | 47 / 47, 9 files |
| Frontend build | `pnpm build` in `apps/web` | ~350 kB / ~108 kB gz |
| E2E (Playwright) | `make e2e` | 2 / 2, 5-run zero-flake |
| CI enforcement | `.github/workflows/ci.yml` | 5 jobs: `test`, `web`, `e2e`, `governance`, `secret-scan` |

Branch-protection settings in GitHub require all five jobs —
operator checklist at `docs/runbooks/ci-cd.md` §3.

---

## Measurement snapshot (VS1 Chunk F, 2026-04-24)

5 consecutive clean `make e2e` runs:

| Run | Clicks to encounter | Note-save (ms) |
|---|---|---|
| 1 | 3 | 415 (cold) |
| 2 | 3 | 158 |
| 3 | 3 | 176 |
| 4 | 3 | 145 |
| 5 | 3 | 148 |
| **Median** | **3 / 3** | **158 ms** (≈ 570× under the 90 s DoD) |

PHI-leakage scan result across all 5 runs:

- `localStorage` keys: 0
- `sessionStorage` keys: 0
- `document.cookie` length: 0 chars
- `console.*` lines: 4 (React Router future-flag warnings, no PHI)
- Sentinels leaked (given / family / MRN / birth date / note body): 0
- Bearer-token string leaked: no

The generous DoD margin reflects VS1's deliberately thin note
surface (free-text only, no templates / sections / signing). As
Phase 4D full exit adds those, the measurement tightens toward
the bound.

---

## Governance posture

- **Tier trailers** (`Tier:`, `Roadmap-Phase:`, carry-forward) —
  present on all Chunks C..G. Chunks A/B predate the discipline;
  the incident note records the disposition. CI enforces the
  `Roadmap-Phase:` trailer on every future commit via the
  `governance` job.
- **PHI discipline** — zero PHI in `localStorage` /
  `sessionStorage` / cookies / `console.*` (proven by Chunk F);
  zero PHI in audit reason slugs (proven per-chunk by
  backend integration tests). Container-level `data-phi` on
  every PHI-rendering card (pattern from Chunk C).
- **Audit chain** — append-only per ADR-003. Every test cleanup
  + the E2E seeder explicitly preserves `audit.audit_event`.
- **Append-only note model** — every save mints a new row.
  Future amendment workflow (Phase 4D) will layer on `amends_id`
  without mutating existing rows.
- **Forward-only naming** (Rule 07) — no retroactive rename
  sweeps across VS1.

---

## Explicit non-scope for VS1 (carry-forward to Phase 4C / 4D full exit)

- Multi-encounter / multi-note history surfaces per patient.
- Encounter state transitions (FINISH / CANCEL).
- Provider attribution.
- Note signing workflow (immutable-once-signed).
- Note amendments.
- Note templates (SOAP / H&P / progress / procedure).
- Structured sections (subjective / objective / etc.).
- FHIR `Encounter` + `DocumentReference` read endpoints.
- Design-system phase — tokens, typography, icon set, dark mode,
  density modes, component library under `packages/ui/`.
- `packages/api-client` + `packages/schemas`-driven types.
- i18n + locale-aware date formatting.
- Ctrl/Cmd-Enter save shortcut.
- A11y (axe) audit in E2E.
- Visual regression (Percy / Chromatic / Playwright snapshots).
- Multi-browser matrix in CI (Firefox, WebKit).
- Mobile / tablet viewports.
- Lighthouse CI / performance budgets.
- Deploy pipelines (3I.3..3I.6).

The full consolidated carry-forward list is in
`docs/project-timeline.md` under "Open carry-forward (as of VS1 Chunk G)".

---

## What's next

After VS1 wraps, direction is an operator decision. The three
natural successors are:

| Direction | What it means | Entry |
|---|---|---|
| **Clinical depth** | Phase 4C + 4D full exits — state machine, encounter list, signing, amendments, templates, structured sections. | 4C entry already met |
| **Design system + UX** | Phase 3L-adjacent — design tokens, typography scale, `packages/ui`, density modes, dark mode, icon set, Storybook. | VS1 |
| **Platform hardening** | Phase 3I.3..3I.6 — Docker, Terraform, Secrets Manager, deploy pipeline; plus Flyway out-of-process. | 3H landed |

Each is a multi-chunk slice in its own right. Pick one; don't
fan out on all three at once.
