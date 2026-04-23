# CI/CD Runbook — Phase 3I.2

This runbook describes the CI gates landed in Phase 3I.2 and the
branch-protection configuration that enforces them. CI itself is
defined declaratively in `.github/workflows/ci.yml`; this document
covers operator-facing concerns (how to interpret failures, how to
configure GitHub to require these gates).

---

## 1. Gate overview

Every PR against `main` and every push to `main` triggers three
parallel jobs:

| Job | Runtime | Fails when |
|---|---|---|
| **test** | ~3–5 min | `./gradlew test` in `apps/api` fails (compile error, unit-test failure, integration-test failure, ArchUnit violation, Flyway migration checksum mismatch) |
| **governance** | ~10 sec | Any commit in the PR range lacks a non-empty `Roadmap-Phase:` trailer OR `docs/product/*.md` has a passed `next_review:` without a `Review-Deferred:<reason>` trailer in the PR |
| **secret-scan** | ~20 sec | Gitleaks detects any known secret pattern in the working tree or git history |

All three jobs run in parallel — a PR that fails fast on one
gate still reports the other two for the contributor.

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

### 2.2 `governance` job

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

### 2.3 `secret-scan` job

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
   - `governance (trailer + doc-staleness)`
   - `secret-scan (gitleaks)`
   (If the names don't appear, trigger the workflow once by
   pushing a dummy commit or opening a draft PR — the names
   populate after the first run.)
8. Optional but recommended: **Require linear history** (forces
   rebase / squash-merge; simpler `git log --oneline` on main)
9. Save the rule.

After save, PRs that fail any of the three jobs are blocked from
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
footprint per PR (observed, not projected):

- test job: ~3–5 min (dominated by Testcontainers spin-up +
  integration tests)
- governance job: ~10 sec (git log iteration + Python)
- secret-scan job: ~20 sec (gitleaks binary download + scan)

**Caching:** `gradle/actions/setup-gradle@v4` caches Gradle
wrapper + dependencies between runs. First run on a fresh
dependency tree takes ~2 min longer; subsequent runs see the
cache. ArchUnit + Testcontainers artifacts are also cached.

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

Frontend gates (`pnpm typecheck`, `pnpm test`) land alongside
Phase 3L (UX foundation) when `apps/web` has meaningful code.

*Last reviewed: 2026-04-23 (Phase 3I.2 landing).*
