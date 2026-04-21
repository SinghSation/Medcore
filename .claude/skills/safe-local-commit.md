---
name: safe-local-commit
description: Use before any local git commit. Classifies the slice into the tier model from ADR-004, runs the mandatory pre-commit checklist, stages only intended files, produces a Conventional Commit, reports the hash, and — for Tier 1 — continues into push. Never amends, rebases, merges, tags, force-pushes, or deletes branches.
---

# Skill: safe-local-commit

Medcore permits local git commits (and, for Tier 1, autonomous pushes)
by the assistant only under the tiered guardrails defined in
`AGENTS.md` §§4.7–4.8 and ADR-004. This skill is the procedure. Follow
it step-by-step. Do NOT improvise. Do NOT batch steps. If any step
fails, STOP and report — do not proceed.

## Step 0 — Classify the slice

Determine the tier **before** doing anything else. Per ADR-004 §2.1:

**Tie-breaker first:** if the slice spans multiple tiers OR
classification is ambiguous, the **highest tier touched applies to
the entire slice**. Mixed-tier slices are never classified down.
Walk each changed file through the tier tests below and take the
max.

- **Tier 3 (high-risk)** if the diff touches any of: authentication,
  authorization, audit logging, non-additive migrations,
  `infra/**`, dependency manifests / lockfiles, secrets, PHI paths,
  `packages/schemas/fhir/**`, governance files (`AGENTS.md`,
  `.cursor/rules/**`, `.claude/**`, `docs/adr/**`,
  `docs/architecture/**`), or any area you cannot confidently
  classify.
- **Tier 2 (medium-risk)** if not Tier 3 and the diff touches:
  purely additive migrations (new tables, nullable-no-default
  ADD COLUMN, CREATE INDEX, grant/type expansions — see ADR-004
  §2.1 for the full allow/deny list; anything outside it is Tier 3),
  new non-FHIR OpenAPI contracts, new endpoints / controllers /
  services outside Tier 3 areas, test infrastructure affecting
  production-posture guarantees, or security-adjacent runbooks.
- **Tier 1 (safe)** otherwise.

Record the tier choice AND the rationale. Both will appear in the
commit body (`Tier: N`) and the final report.

## Step 1 — Check overrides

If the human has issued any of the following in the current session,
obey before proceeding:

- **"hold"** — stop immediately.
- **"review only"** / **"draft only"** — produce the review pack;
  skip commit.
- **"do not commit"** — skip commit.
- **"do not push"** — may commit if the tier permits; must not push.

## Step 2 — Preconditions (hard gate, all tiers)

Verify ALL of `AGENTS.md` §4.7.3 universal preconditions:

- [ ] Task complete within the agreed scope.
- [ ] Full list of changed files shown and summarized.
- [ ] All required validations run in the current session and passing.
- [ ] No forbidden content staged (secrets, `.env`, PHI, OS junk,
      build artifacts, out-of-scope files).
- [ ] Commit body will carry a **`Tier: N`** line and a
      carry-forward list (§4.7.5).

Also verify the universal guardrails (§4.7.4) — none must be
triggered:

- [ ] No failing tests.
- [ ] No conflict with an accepted ADR.
- [ ] No conflict with `AGENTS.md` or `.cursor/rules/**`.
- [ ] No high-risk area touched that the review pack did not flag.
- [ ] No unclear migration safety.
- [ ] No ambiguity in tier classification.

If any precondition fails or any guardrail trips, STOP.

## Step 3 — Tier-specific authority check

**Tier 3:** verify in the session log:

- [ ] The exact phrase **"approved to commit"** (paraphrases do NOT
      count).
- [ ] An explicit per-change approval naming the high-risk area(s).
- [ ] Scope of that approval matches what is about to be committed.

If any is missing, STOP and request the missing phrase.

**Tier 2:** no commit phrase required. Proceed to Step 4.
(The `"approved to push"` phrase will be required at Step 9.)

**Tier 1:** no commit phrase required. Proceed to Step 4.

## Step 4 — Inspect the working tree

Run and show the human:

- `git status --short`
- `git diff --stat`
- `git diff --cached --stat` (if anything is already staged)
- `git branch --show-current`

Do NOT stage anything yet.

## Step 5 — Forbidden-content sweep

Run a secrets scan on the working tree (`make secrets-scan`, or
`gitleaks detect --no-banner --redact --source .` where available).
If the scanner reports any finding, STOP. Also grep for obvious
secret patterns and PHI-shaped fixtures; any hit is a STOP.

## Step 6 — Summarize

Produce a written summary, shown to the human, containing:

- Tier classification + short rationale.
- The exact list of files that will be committed.
- A one-paragraph description of what changed and why.
- The high-risk areas touched (if any).
- The validations run and their results.
- The proposed Conventional Commit message (including `Tier: N` line
  and carry-forward list).

For Tier 3, confirm the summary matches what the human approved. If
anything differs, STOP and reconfirm.

## Step 7 — Stage precisely

Stage ONLY the files named in the summary. Use explicit paths:

```
git add -- <path> <path> …
```

Do NOT use `git add .`, `git add -A`, or `git add -u`. Do NOT stage
by glob or pattern.

After staging, run `git diff --cached --stat` and `git diff --cached`.
Confirm the staged content matches the summary exactly. If it does
not, unstage with `git restore --staged -- <path>` and retry.

## Step 8 — Commit

Use Conventional Commits (`type(scope): subject`). The body MUST
include:

- Rationale / citations (ADRs, invariants).
- A line **`Tier: N`** recording the classification.
- A **carry-forward list** of intentionally deferred items (even if
  the slice added nothing new — carry forward from prior slices).

Do NOT use `--amend`, `--no-verify`, `--no-gpg-sign`, or `--signoff`
unless the human has explicitly asked.

Let pre-commit hooks run. If a hook fails:

1. STOP.
2. Report the failure output verbatim.
3. Do NOT retry with `--no-verify`.
4. Do NOT `--amend` — the prior commit did not occur; `--amend` would
   modify the PREVIOUS commit instead.
5. Fix the underlying issue with the human's guidance, re-stage, and
   create a NEW commit in a fresh run of this skill.

## Step 9 — Push (tier-dependent)

**Tier 1:** proceed directly to push with `git push origin main`.
No phrase required. The universal guardrails in Step 2 still apply —
if any condition changed between Step 8 and here, STOP.

**Tier 2:** STOP and request the exact phrase **"approved to push"**.
Do not push before the human utters it. Once uttered, proceed to
`git push origin main`.

**Tier 3:** STOP and request the exact phrase **"approved to push"**.
Do not push before the human utters it. Once uttered, proceed to
`git push origin main`.

Force-push to `main` or any protected branch is prohibited at every
tier, including with "approved to push"; it requires a separate,
written authorization naming the branch and the reason.

## Step 10 — Report

Report to the human:

- Commit hash (`git rev-parse HEAD`).
- Branch (`git branch --show-current`).
- Tier classification.
- The exact commit message.
- Any hook output.
- `git status --short` post-commit (expect clean; otherwise explain
  each remaining entry).
- Push status: pushed (hash range) or not-pushed-and-why.

## What this skill does NOT do

This skill is commit-focused (and, for Tier 1, proceeds into push).
It NEVER performs:

- `git push --force` or any force variant.
- `git commit --amend`.
- `git rebase` or `git merge`.
- `git tag`.
- Branch creation, deletion, or renaming.
- Any history rewriting.

Those operations are governed by `AGENTS.md` §4.8 and require the
phrase **"approved to push"** at every tier, plus for force-push to
protected branches an additional named authorization.

## Violations

A commit made without satisfying every step above is a governance
incident. The assistant MUST report it immediately, propose a
remediation (typically: a follow-up revert commit plus redo under
this skill), and await human direction.
