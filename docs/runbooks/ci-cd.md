# CI/CD Runbook

This runbook describes the CI gates wired in
`.github/workflows/ci.yml` and the branch-protection configuration
that enforces them. CI itself is defined declaratively; this
document covers operator-facing concerns (how to interpret
failures, how to configure GitHub to require these gates).

Originally landed in Phase 3I.2 (backend + governance + secret
scanning). Extended in VS1 Chunk G (Phase 4D.4) with the `web`
and `e2e` gates so the DoD + PHI-leakage discipline proved
locally in Chunk F is enforced at merge time, not by developer
memory.

---

## 1. Gate overview

Every PR against `main` and every push to `main` triggers five
jobs:

| Job | Runtime (est.) | Fails when |
|---|---|---|
| **test** | ~3–5 min | `./gradlew test` in `apps/api` fails (compile error, unit-test failure, integration-test failure, ArchUnit violation, Flyway migration checksum mismatch) |
| **web** | ~1–2 min | `pnpm typecheck`, Vitest, or `pnpm build` in `apps/web` fails |
| **e2e** | ~3–5 min | Playwright happy-path DoD fails OR the PHI-leakage scan finds any sentinel in `localStorage` / `sessionStorage` / `document.cookie` / `console.*`. Requires `test` + `web` to pass first (no sense spinning up Postgres + API + Vite when a PR already fails cheaper gates). |
| **governance** | ~10 sec | Any commit in the PR range lacks a non-empty `Roadmap-Phase:` trailer OR `docs/product/*.md` has a passed `next_review:` without a `Review-Deferred:<reason>` trailer in the PR |
| **secret-scan** | ~20 sec | Gitleaks detects any known secret pattern in the working tree or git history |

`test`, `web`, `governance`, `secret-scan` run in parallel. `e2e`
runs after `test` + `web` have passed. A PR that fails fast on one
gate still reports the others to the contributor.

---

## 2. Interpreting failures

### 2.1 `test` job

**Symptom:** "Run gradle test" step fails.

**Remediation matrix:**

- **Compile error:** fix the source. Check the Gradle output for
  the file + line.
- **Integration-test failure:** check `apps/api/build/reports/tests/test/`
  — it's uploaded as an artifact when the job fails (7-day
  retention on GitHub's storage). Download from the job's
  "Artifacts" section.
- **ArchUnit violation:** rule name + violation locations are in
  the failure message. Each rule carries an `.as(...)` text
  describing the architectural contract; the fix is either (a)
  move the offending code to a sanctioned package, (b) extend
  the rule's allow-list with documented rationale (needs
  review-pack callout per ADR-005 §2.3).
- **Flyway failure:** almost always a checksum mismatch caused
  by editing an already-applied migration. `V*` files are
  immutable once landed; create a new `V(N+1)` migration.

### 2.2 `web` job

**Symptom 1:** "Typecheck" step fails.

Run `pnpm typecheck` locally from `apps/web` — the error list
will match CI. Common causes:

- Unused export removed while a caller still imports it
  (`noUnusedLocals` + dead import).
- E2E fixture imports that drift from the source module they
  reference (`tsconfig.e2e.json` participates in typecheck too).

**Symptom 2:** "Vitest" step fails.

Run `pnpm test` locally. `apps/web/vite.config.ts` scopes Vitest
to `src/**/*.test.ts[x]` and explicitly excludes `e2e/` — a
failure here is a unit- or component-level regression, not an E2E
issue.

**Symptom 3:** "Build" step fails.

Run `pnpm build` locally. Usually a stricter-than-vitest
TypeScript check (the build runs `tsc -b`) or a Tailwind / asset
resolution error.

### 2.3 `e2e` job

**Symptom 1:** "Wait for API health" or "Wait for Vite" times out.

The in-job wait loops poll `/actuator/health` and `/` for up to
120 / 60 seconds respectively. When they time out, the artifact
`service-logs` (uploaded on failure) contains the full API +
Vite stdout/stderr. Common causes:

- Flyway migration checksum drift — look for
  `FlywayValidateException` in `api.log`.
- Postgres service container not ready before the API tried to
  connect — the service container has a `pg_isready` health
  check with 10 retries; if Postgres genuinely takes longer
  than ~50 s to start on the runner, escalate to a repo issue.
- `medcore_app` password sync failure — check for
  `MedcoreAppPasswordSync` log lines. The CI env sets
  `MEDCORE_DB_APP_PASSWORD_SYNC_ENABLED=true`; the sync runs
  `ALTER ROLE medcore_app WITH PASSWORD ...` after Flyway.
  If the role doesn't exist yet, the migration that creates it
  (`V10__runtime_role_grants.sql` in identity) failed to apply.

**Symptom 2:** Playwright reports one or more failing specs.

Download the `playwright-report` artifact — the HTML report
includes per-test traces, screenshots, and the exact assertion
that failed. `playwright-test-results` contains the raw trace
ZIP files which you can open with
`pnpm exec playwright show-trace <trace.zip>` locally for
step-by-step replay.

**Symptom 3:** The PHI-leakage spec fails.

The assertion message names the specific sentinel that leaked
(patient given name / family name / MRN / birth date / note
body / bearer-token string) and the surface it appeared in
(storage / cookie / console). This is a **merge-blocker** — PHI
in browser storage, cookies, or console violates HIPAA §164.312
technical safeguards. Do NOT bypass; fix at the source.

**Symptom 4:** The DoD assertion fails (> 3 clicks or > 90 s).

Roadmap exits under Phase 4C and 4D commit Medcore to specific
user-facing bounds. A regression here isn't a flake — it's a
product-surface contract violation. Fix the regression; do NOT
weaken the bound without an explicit ADR and user-facing
stakeholder signoff.

### 2.4 `governance` job

**Symptom 1:** "Check Roadmap-Phase trailer" step lists commits
without the trailer.

Every commit body on the PR branch MUST include a line like:

```
Roadmap-Phase: 3I.2 (CI enforcement)
```

Remediation: squash / amend the offending commit(s) to add the
trailer. For an in-flight PR, easiest is
`git rebase -i origin/main`, reword each commit to add the line,
force-push.

**Symptom 2:** "Check doc staleness" fails with "next_review
was <date>."

One of the `docs/product/*.md` files has a `next_review:` date
in the past. Remediation (pick one):

1. **Bump the date.** Edit the file's frontmatter: update
   `last_reviewed:` to today, `next_review:` to the appropriate
   next date per the doc's `cadence:` field. Commit as a Tier 2
   doc-review touch per ADR-005 §2.3.
2. **Defer the review.** Add a trailer to a commit in this PR:
   ```
   Review-Deferred: waiting for Phase 4A scope to finalize UX constraints
   ```
   The reason MUST be non-empty. Empty `Review-Deferred:` lines
   are rejected by the check (ADR-005 §2.5 refinement —
   deferrals must be reviewable in git history).

### 2.5 `secret-scan` job

**Symptom:** "Run gitleaks" step reports one or more findings.

**CRITICAL.** Every finding is a potential secret that has
entered the repository. Do NOT simply delete the file and force-
push — the secret is already in the repo's git history, which
anyone with clone access has seen.

Remediation steps (in order):

1. **Rotate the leaked credential immediately.** AWS keys,
   tokens, passwords — treat them as compromised the moment
   they're in any git commit, even briefly.
2. **Rewrite the history** if the repo is private + the clone
   surface is known (`git filter-repo` or `git filter-branch`).
   If the repo is public, history rewriting is moot — the
   secret was visible to the world for its exposure window;
   rotation is the only real remediation.
3. **Push the fix + the history rewrite.** Gitleaks will
   re-run and (assuming rotation + rewrite succeeded) pass.
4. **Document the incident** in an ADR or incident note
   depending on severity.

False positives: extend `.gitleaks.toml` allow-list with the
specific path + regex. Common false positives are test-only
fixture strings (mock-oauth2-server bearer tokens, etc.) —
allow-list those narrowly, never globally.

---

## 3. Branch-protection configuration

**This step is NOT automated.** GitHub requires the workflow to
have run at least once before the status-check names are
discoverable in the UI. Configure these settings once after the
first PR using the new workflow lands:

1. Navigate to: **Settings → Branches → Branch protection rules**
2. Click **Add rule** (or edit the existing `main` rule)
3. Branch name pattern: `main`
4. Check **Require a pull request before merging**
   - Optional: require at least 1 approval (solo-dev repos can
     skip; multi-dev repos should require ≥1)
5. Check **Require status checks to pass before merging**
6. Check **Require branches to be up to date before merging**
7. In the status-check search box, add each of:
   - `test (gradle + ArchUnit + migrations)`
   - `web (pnpm typecheck + test + build)`
   - `e2e (Playwright live stack)`
   - `governance (trailer + doc-staleness)`
   - `secret-scan (gitleaks)`
   (If the names don't appear, trigger the workflow once by
   pushing a dummy commit or opening a draft PR — the names
   populate after the first run. `e2e` only runs after `test`
   and `web` pass, so it may take two workflow cycles for its
   name to surface.)
8. Optional but recommended: **Require linear history** (forces
   rebase / squash-merge; simpler `git log --oneline` on main)
9. Save the rule.

After save, PRs that fail any of the five jobs are blocked from
merging in the GitHub UI. The only bypass is a repo admin
pressing "merge anyway" with a documented override — that
bypass should be logged in the PR body.

---

## 4. Editing the workflow

`.github/workflows/ci.yml` is a **Tier 3** file — changes to CI
gate logic affect merge-time enforcement. Standard Tier 3 rules
apply (ADR-004):

- Changes go through PR review (even for the repo owner's own
  PRs).
- The change's commit body MUST include `Roadmap-Phase:` as
  always — but the governance job runs the OLD rules until the
  PR is merged, so a PR that introduces a new rule doesn't
  accidentally fail the old gate.
- Adding a new gate: write the rule + document in §2 of this
  runbook + land as a separate slice (typically 3I.2.x or
  similar).
- Removing a gate: requires an ADR explaining what compensating
  control replaces it.

---

## 5. Cost + performance

GitHub Actions is free for public repos; for private repos,
`ubuntu-latest` consumes standard compute minutes. Current
footprint per PR (estimated):

- test job: ~3–5 min (dominated by Testcontainers spin-up +
  integration tests)
- web job: ~1–2 min (pnpm install + typecheck + Vitest + build)
- e2e job: ~3–5 min (pnpm install + Playwright browser + API
  bootJar + service warm-up + 2 specs). Runs sequentially after
  `test` + `web` — total wall-clock cost is ~test + ~e2e.
- governance job: ~10 sec (git log iteration + Python)
- secret-scan job: ~20 sec (gitleaks binary download + scan)

**Caching:**

- `gradle/actions/setup-gradle@v4` caches Gradle wrapper +
  dependencies. First run on a fresh dependency tree takes
  ~2 min longer; subsequent runs see the cache. ArchUnit +
  Testcontainers artifacts also cached.
- `actions/setup-node@v4` with `cache: 'pnpm'` caches the pnpm
  store between runs. Invalidated by `pnpm-lock.yaml` hash
  change.
- `actions/cache@v4` on `~/.cache/ms-playwright` caches the
  Chromium headless-shell binary (~90 MB). Invalidated by
  `pnpm-lock.yaml` hash change (catches Playwright version
  bumps via devDep updates).

**Concurrency:** no concurrency limits set — each PR push runs
a fresh workflow. If cost becomes a concern, add:

```yaml
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true
```

which cancels in-flight runs when a newer commit is pushed to
the same branch.

---

## 6. Escalation

- **CI infrastructure issues** (GitHub Actions outage): no
  direct escalation — wait for status.github.com; merge-freeze
  in effect during the outage.
- **Repeated false positives on a gate**: file an issue, triage
  as a runbook / rule update. Don't disable the gate without
  an ADR.
- **Urgent merge during a gate outage**: document the override
  in the PR body, merge with admin privileges, create a
  follow-up issue to run the skipped gate manually + close.

---

## 7. Future extensions (tracked in the roadmap)

Phase 3I.2 ships the core governance gates. Planned additions:

- **3I.2.b** — `ktlintCheck` + `detekt` (Kotlin code style + static
  analysis). Deferred to a dedicated slice so the baseline-file
  curation doesn't churn this runbook.
- **3I.3** — Dockerfile + build-info plugin for deployable
  artifacts.
- **3I.4** — Terraform `dev` environment (VPC, ECS Fargate, RDS,
  S3, CloudWatch).
- **3I.5** — AWS Secrets Manager real implementation; removes
  `MedcoreAppPasswordSync` + `JpaDependsOnPasswordCheck`.
- **3I.6** — Deploy-on-merge workflow (ECR push + ECS service
  update + Flyway migrator pre-deploy task).

Frontend `web` + `e2e` gates landed in VS1 Chunk G (Phase 4D.4)
— see VS1 evidence pack at `docs/evidence/vs1-review-pack.md`.

Still pending:

- **Dependabot / Renovate** policy for pnpm + gradle — separate
  governance slice.
- **Firefox + WebKit matrix** on the `e2e` job — post-VS1
  browser-hardening slice.
- **Lighthouse CI / Playwright performance budgets** — post-VS1.
- **CODEOWNERS + required-reviewer matrix** — separate
  governance slice.
- **Flake dashboard / historical trend** — later observability
  slice.

*Last reviewed: 2026-04-24 (VS1 Chunk G landing).*
