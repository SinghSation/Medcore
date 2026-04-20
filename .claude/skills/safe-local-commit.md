---
name: safe-local-commit
description: Use whenever the human has said the exact phrase "approved to commit". Runs the mandatory pre-commit checklist, stages only intended files, produces a Conventional Commit, and reports the hash. Never pushes, amends, rebases, merges, tags, or deletes branches.
---

# Skill: safe-local-commit

Medcore permits **local** git commits by the assistant only under the
strict guardrails defined in `AGENTS.md` §4.7. This skill is the
procedure. Follow it step-by-step. Do NOT improvise. Do NOT batch
steps. If any step fails, STOP and report — do not proceed.

## Preconditions (hard gate)

Before running any git command, verify ALL of the following:

- [ ] The human has said the exact phrase **"approved to commit"** in
      the current session. Paraphrases (e.g., "go ahead and commit",
      "commit it", "yes commit", "lgtm") do NOT count. If the phrase is
      absent, STOP and ask for it.
- [ ] The approval covers the specific change about to be committed,
      not a prior or unrelated change.
- [ ] The task is complete within the agreed scope.
- [ ] All required validations for the touched area have been run and
      reported as passing **in the current session**. Stale results
      from earlier turns do not count.
- [ ] The request is a commit only. No `push`, `--amend`, `rebase`,
      `merge`, `tag`, branch deletion, or history rewrite is being
      attempted — those are governed by `AGENTS.md` §4.8 and REQUIRE
      **"approved to push"**.

If any precondition fails, STOP.

## Step 1 — Inspect the working tree

Run and show the human the output of:

- `git status --short`
- `git diff --stat`
- `git diff --cached --stat` (if anything is already staged)
- `git branch --show-current`

Do NOT stage anything yet.

## Step 2 — Classify the change

Determine whether the change touches any **high-risk area** defined in
`AGENTS.md` §4.7:

- Authentication, authorization, audit logging.
- Database migrations (`**/migrations/**`).
- Infrastructure / Terraform (`infra/**`).
- Dependency manifests or lockfiles.
- Encryption, key management, secrets handling.
- PHI-handling code paths.
- Interoperability / FHIR contracts (`packages/schemas/fhir/**`).
- Governance files (`AGENTS.md`, `.cursor/rules/**`, `.claude/**`,
  `docs/adr/**`, `docs/architecture/**`).

If the change touches ANY high-risk area and the human has not given a
**specific per-change approval** in the current session (beyond the
generic "approved to commit"), STOP, list the high-risk areas touched,
and request the explicit per-change approval.

## Step 3 — Forbidden-content scan

Refuse to stage any file that is, or contains, any of the following:

- A secret, API key, token, credential, private key, or certificate.
- A `.env` file (anything starting with `.env` other than
  `.env.example` or `.env.*.example`).
- PHI, PII, or fixtures resembling real patient data.
- OS junk: `.DS_Store`, `Thumbs.db`, `._*`, `Desktop.ini`, editor swap
  / backup files, `*.swp`, `*.swo`, `*~`.
- Build artifacts: `dist/`, `build/`, `node_modules/`, `.next/`,
  `.turbo/`, `.cache/`, coverage output.
- Files outside the agreed scope of the task.

Run a secrets scan on the working tree (`make secrets-scan`, or
`gitleaks detect --no-banner --redact --source .` where available). If
the scanner reports any finding, STOP.

## Step 4 — Summarize

Produce a written summary, shown to the human, containing:

- The exact list of files that will be committed (full paths).
- A one-paragraph description of what changed and why.
- The classification (low-risk / high-risk, with the list of areas).
- The validations run and their results.
- The proposed Conventional Commit message.

Confirm the human's approval covers this exact summary. If anything
differs from what they approved, STOP and reconfirm.

## Step 5 — Stage precisely

Stage ONLY the files named in the summary. Use explicit paths:

```
git add -- <path> <path> …
```

Do NOT use `git add .`, `git add -A`, or `git add -u`. Do NOT stage by
glob or pattern.

After staging, run `git diff --cached --stat` and `git diff --cached`.
Confirm the staged content matches the summary exactly. If it does
not, unstage with `git restore --staged -- <path>` and retry.

## Step 6 — Craft the message

Use Conventional Commits:

```
<type>(<scope>): <subject>

<body>

<footer>
```

- `type` ∈ { `feat`, `fix`, `docs`, `refactor`, `test`, `chore`,
  `build`, `ci`, `perf`, `style`, `revert`, `governance` }.
- `scope` is the module or area (e.g., `schemas`, `api`, `web`,
  `infra`, `ci`, `governance`).
- `subject` is imperative mood, ≤72 characters, no trailing period.
- `body` explains the *why*; reference invariants (`AGENTS.md` §3.x)
  or ADR numbers where applicable.
- `footer` references issues when relevant.

The `Co-Authored-By` trailer is included only when the human has
explicitly requested attribution, or when the repository's global
commit convention requires it — otherwise omit.

Examples:

- `governance(agents): formalize controlled commit authority`
- `docs(adr): add ADR-007 on tenant isolation strategy`
- `chore(repo): add .editorconfig and harden .gitignore`

## Step 7 — Commit

Run a standard commit. Do NOT use `--amend`, `--no-verify`,
`--no-gpg-sign`, or `--signoff` unless the human has explicitly asked.
Prefer a HEREDOC for multi-line bodies.

Let pre-commit hooks run. If a hook fails:

1. STOP.
2. Report the failure output verbatim.
3. Do NOT retry with `--no-verify`.
4. Do NOT `--amend` — the prior commit did not occur; `--amend` would
   modify the previous commit instead.
5. Fix the underlying issue with the human's guidance, re-stage, and
   create a NEW commit in a fresh run of this skill.

## Step 8 — Report

After a successful commit, report to the human:

- Commit hash: `git rev-parse HEAD`.
- Branch: `git branch --show-current`.
- The exact commit message.
- Any hook output.
- `git status --short` after the commit (should be clean, or show only
  intentionally unstaged / untracked files with a note explaining why).

## What this skill does NOT do

This skill is **commit-only**. It never performs:

- `git push` in any form.
- `git commit --amend`.
- `git rebase` or `git merge`.
- `git tag`.
- Branch creation, deletion, or renaming.
- Any history rewriting.

Those operations are governed by `AGENTS.md` §4.8 and require the
phrase **"approved to push"** (and, for protected branches,
additional named authorization).

## Violations

A commit made without satisfying every step above is a governance
incident. The assistant MUST report it immediately, propose a
remediation (typically: a follow-up revert commit plus redo under this
skill), and await human direction.
