# AGENTS.md — Medcore AI Operating Contract

> This file is the **authoritative operating contract** for every AI agent
> (Claude Code, Cursor, Copilot, autonomous tools) that writes, edits, reviews,
> or executes code in this repository.
>
> Humans MUST read this document before delegating work. Agents MUST treat
> every rule below as a hard constraint. If a user instruction conflicts with
> this file, the agent MUST stop, flag the conflict, and request explicit
> human confirmation. Agents MUST NOT silently override governance.

---

## 1. Product Context

Medcore is an **enterprise-grade, AI-governed Electronic Health Record (EHR)
platform**. It handles **Protected Health Information (PHI)** and is subject to:

- **HIPAA** (US) — Privacy, Security, and Breach Notification Rules
- **HITECH** — breach notification and audit controls
- **HL7 FHIR R4** — interoperability contracts for clinical data
- **SOC 2 Type II** — security, availability, confidentiality controls
- **21 CFR Part 11** / **GDPR** — where applicable for regulated data

Every architectural, code, and operational decision in this repository is
evaluated against these obligations. "Move fast and break things" is
**explicitly prohibited**.

---

## 2. Repository Structure (Authoritative)

```
medcore/
  .github/workflows/      # CI/CD: lint, test, security scans, policy checks
  .cursor/rules/          # Cursor IDE enforcement rules (.mdc)
  .claude/skills/         # Claude Code reusable procedures (skills)
  .claude/agents/         # Claude Code subagent definitions
  docs/
    architecture/         # Charter, C4 diagrams, system design
    adr/                  # Architecture Decision Records (immutable log)
    compliance/           # HIPAA, SOC 2, HITECH control mappings
    security/             # Threat models, STRIDE, data-flow diagrams
    interoperability/     # FHIR profiles, HL7 mappings, terminology
    runbooks/             # On-call, incident response, recovery procedures
    evidence/             # Audit artifacts for compliance attestations
  apps/
    web/                  # Frontend application (placeholder)
    api/                  # Backend API service (placeholder)
  packages/
    ui/                   # Shared UI component library
    config/               # Shared configuration (lint, tsconfig, env schema)
    schemas/              # FHIR + domain schemas, OpenAPI, Zod, JSON Schema
    api-client/           # Generated typed API client (from OpenAPI)
  infra/
    terraform/            # Infrastructure-as-Code (authoritative)
  scripts/                # Developer and CI automation
```

The structure above is **frozen**. Agents MUST NOT introduce new top-level
directories without a corresponding ADR approved by the human owner.

---

## 3. Non-Negotiable Invariants

These invariants are **inviolable**. A pull request that violates any of them
MUST be rejected by CI and by reviewers, regardless of urgency.

### 3.1 Security & PHI

1. **PHI MUST NEVER** appear in logs, traces, analytics events, error
   messages, screenshots, fixtures, seed data, or AI prompts.
2. **All PHI at rest MUST be encrypted** (AES-256 or stronger). All PHI in
   transit MUST use TLS 1.2+ with modern ciphers.
3. **Secrets MUST NEVER** be committed. Use the approved secret manager.
   `.env.example` files are allowed; `.env` files are forbidden.
4. **Authentication** MUST be centralized. Ad-hoc auth logic is prohibited.
5. **Authorization** MUST be explicit, deny-by-default, and checked at the
   data-access boundary — never only at the UI layer.
6. **Every PHI read and write MUST emit an audit event** (see §3.6).

### 3.2 API Contracts

1. All HTTP APIs MUST be defined **contract-first** in `packages/schemas/`
   (OpenAPI 3.1 for internal APIs; FHIR R4 profiles for clinical data).
2. Clients MUST consume the **generated** `packages/api-client`. Hand-written
   HTTP calls to internal services are prohibited.
3. Breaking changes REQUIRE a new API version and a deprecation ADR.

### 3.3 Database & Migrations

1. Schema changes MUST ship as **reversible, forward-only migrations** with
   explicit up/down logic and a documented rollback plan.
2. Destructive operations (`DROP`, `TRUNCATE`, column removal, NOT NULL on
   populated tables) REQUIRE an ADR and a staged rollout plan.
3. Every table containing PHI MUST have row-level tenancy, audit columns
   (`created_at`, `updated_at`, `created_by`, `updated_by`), and a retention
   policy documented in `docs/compliance/`.

### 3.4 Frontend

1. All user-facing components MUST meet **WCAG 2.1 AA**.
2. PHI-rendering components MUST be explicitly marked and MUST NOT be logged,
   persisted to client storage, or sent to third-party analytics.
3. Client-side state MUST NOT be trusted for authorization.

### 3.5 Testing

1. Every change touching PHI paths REQUIRES unit **and** integration tests.
2. Security-sensitive code paths REQUIRE adversarial / negative tests.
3. `--no-verify`, skipped tests, and disabled CI checks are **forbidden**
   unless accompanied by a tracked remediation issue and a dated waiver.

### 3.6 Audit & Observability

1. Every PHI access event MUST be recorded with: actor, subject, action,
   timestamp, request ID, tenant, and justification (where required).
2. Audit logs MUST be append-only and tamper-evident.
3. All services MUST emit structured logs, metrics, and traces using the
   shared observability conventions (see `.cursor/rules/06-audit-observability.mdc`).

---

## 4. Agent Operating Procedures

### 4.1 Before Writing Code

An agent MUST, in order:

1. Read this file (`AGENTS.md`).
2. Read the relevant `.cursor/rules/*.mdc` files for the area being touched.
3. Read the most recent ADRs in `docs/adr/` that could constrain the change.
4. Identify which invariant (§3) applies, and cite it in the PR description.
5. If the task lacks a clear contract, schema, or ADR — **stop and ask**.

### 4.2 Contract-First Workflow

For any new module or endpoint:

1. Define or update the schema in `packages/schemas/`.
2. Regenerate `packages/api-client/` and any FHIR artifacts.
3. Implement the server against the generated types.
4. Implement the client against the generated client.
5. Add tests at every layer.

See `.claude/skills/api-contract-first.md` and `.claude/skills/new-module.md`.

### 4.3 Database Changes

Use `.claude/skills/safe-db-migration.md`. Agents MUST NOT edit production
schemas through ad-hoc SQL.

### 4.4 PHI Review

Before committing any code that could touch PHI, invoke
`.claude/skills/phi-exposure-review.md` and attach the result to the PR.

### 4.5 Architectural Decisions

Any change that (a) introduces a new dependency of material footprint,
(b) alters a trust boundary, (c) changes data classification, or
(d) modifies the repository structure, REQUIRES an ADR.

Copy `docs/adr/000-template.md`, number it sequentially, and submit it as
part of the same pull request.

### 4.6 Prohibited Actions

Agents MUST NOT, without explicit human authorization in the same turn:

- Delete files, migrations, or any tracked history.
- Install, upgrade, or remove dependencies.
- Run destructive shell commands (`rm -rf`, `DROP`, `TRUNCATE`, `reset --hard`).
- Disable hooks, linters, tests, or CI checks.
- Exfiltrate repository contents to external services beyond what the user
  has explicitly sanctioned.
- Fabricate file paths, function names, library APIs, or compliance
  citations. If unsure, agents MUST state uncertainty.

Git-specific authorities are governed separately by §4.7 (local commits)
and §4.8 (remote and destructive git operations).

### 4.7 Controlled Git Commit Authority (Tiered)

Agents operate under the **Tiered Execution Authority Model** defined
in [ADR-004](./docs/adr/004-tiered-execution-authority-model.md). This
section is a normative summary; the ADR is authoritative for tie-breaks.

Every slice the agent commits MUST be classified into exactly one of
three tiers before commit. The authority discipline below applies per
tier. Regardless of tier, the agent MUST also satisfy the universal
preconditions in §4.7.3 and the universal guardrails in §4.7.4.

#### 4.7.1 Tier classification

**Classification tie-breaker.** If a slice spans multiple tiers, or
if the agent cannot unambiguously assign a single tier, **the highest
tier touched applies to the entire slice**. Mixed-tier slices are
never classified down. A single Tier 3 file riding alongside benign
changes forces Tier 3 for the whole slice.

**Tier 3 — High-risk (phrase-gated, named per-change approval).**
A slice is Tier 3 if its diff touches ANY of:

- Authentication.
- Authorization.
- Audit logging (code paths or emission sites).
- Database migrations that are **not** strictly additive-and-idempotent
  per Rule 03 §"Safety gates" (drops, in-place renames, NOT NULL on
  populated columns, column-type narrowing, unique on populated
  columns, non-trivial backfills, locking-budget-exceeding operations).
- Infrastructure / Terraform (`infra/**`).
- Dependency manifests or lockfiles with material transitive changes.
- Encryption, key management, secrets handling.
- PHI-handling code paths.
- Interoperability / FHIR contracts (`packages/schemas/fhir/**`).
- Governance files (`AGENTS.md`, `.cursor/rules/**`, `.claude/**`,
  `docs/adr/**`, `docs/architecture/**`).
- Any area the agent is not confident classifying.

Tier 3 requires, in the current session:

1. The exact phrase **"approved to commit"**. Paraphrases ("go ahead",
   "commit it", "yes commit", "lgtm") do NOT count.
2. An explicit per-change approval naming the high-risk area(s).
3. The exact phrase **"approved to push"** before any push.

All three are **single-use and scoped to the preceding turn** — see
§4.8.

**Tier 2 — Medium-risk (commit autonomous; push phrase-gated).**
A slice is Tier 2 if it is not Tier 3 AND its diff touches ANY of:

- **Purely additive Flyway migrations**, strictly defined (see
  ADR-004 §2.1). Allowed: `CREATE TABLE` of new tables; `ADD COLUMN`
  that is NULL-allowed with no default; `CREATE INDEX` on a new or
  existing column; role/grant expansions; `CREATE SCHEMA` / `CREATE
  TYPE` for new names. Forbidden (any → Tier 3): `DROP`, `RENAME`,
  `ALTER COLUMN`, constraint addition on populated tables
  (CHECK/UNIQUE/FK/NOT NULL), default changes, backfill of any size,
  `TRUNCATE`, `REVOKE` that reduces grants, explicit lock/timeout
  directives. Ambiguous → Tier 3.
- New API contract files under `packages/schemas/openapi/**` (not FHIR).
- New endpoints, controllers, or service classes outside Tier 3
  areas AND outside the identity / tenancy / audit modules'
  auth-sensitive code paths.
- Test infrastructure that materially affects the test harness's
  production-posture guarantees.
- Developer runbooks touching security-adjacent topics that are not
  themselves governance files.

A slice whose diff includes an additive migration AND any Tier 3
trigger is Tier 3 by the tie-breaker above.

Tier 2 permits autonomous commit provided §§4.7.3 and 4.7.4 are
satisfied. Pushing still requires the exact phrase
**"approved to push"** (single-use, scoped to the preceding turn).

**Tier 1 — Safe (autonomous commit AND push).**
A slice is Tier 1 if none of the Tier 2 or Tier 3 criteria apply AND:

- No database migrations of any kind.
- No governance files.
- No code under `platform/security/**`, `platform/audit/**`, or any
  feature module's auth/authz/audit emission sites.
- No dependency manifest or lockfile changes.
- No FHIR or other interoperability contracts.

Tier 1 permits autonomous commit AND push in one continuous pass
provided §§4.7.3 and 4.7.4 are satisfied.

#### 4.7.2 Override phrases

Any of the following, issued by the human in the current session,
overrides the default tier:

- **"hold"** — pause all work.
- **"review only"** / **"draft only"** — prepare the slice and review
  pack; do not commit, do not push.
- **"do not commit"** — as named.
- **"do not push"** — as named; may commit if tier permits.
- **"approved to commit"** / **"approved to push"** — explicit Tier 3
  authority; supersedes tier autonomy.

#### 4.7.3 Universal preconditions (all tiers)

Every commit at every tier requires ALL of the following:

1. The requested task is complete within the agreed scope. Partial
   work, half-finished refactors, and "checkpoint" commits are
   prohibited.
2. The agent has shown the full list of changed files and has
   summarized, in writing, what changed and why (the "review pack" or
   its inline equivalent).
3. All required validations for the touched area have been run in the
   current session and reported as passing: formatters, linters, type
   checks, tests, `make verify`, and any area-specific gates required
   by the `.cursor/rules/*.mdc` files that match the change.
4. No forbidden content is staged. Forbidden content includes:
   - Secrets, API keys, tokens, credentials, private keys, certificates.
   - `.env` files (only `.env.example` / `.env.*.example` are permitted).
   - PHI, PII, or fixtures resembling real patient data.
   - OS junk (`.DS_Store`, `Thumbs.db`, `._*`, `Desktop.ini`, editor
     swap / backup files).
   - Build artifacts (`dist/`, `build/`, `node_modules/`, `.next/`,
     `.turbo/`, coverage output).
   - Files outside the agreed scope of the task.
5. The commit body carries a **`Tier: N`** line plus a
   **carry-forward list** enumerating intentionally deferred items.

#### 4.7.4 Universal guardrails (all tiers)

Agents MUST halt, produce a stop-and-flag report, and await human
direction — regardless of tier — if ANY of the following is true:

1. Any required test is failing.
2. The diff appears to conflict with an accepted ADR.
3. The diff appears to conflict with this document or any file under
   `.cursor/rules/**`.
4. Secrets, PHI, or any other forbidden pattern per §4.7.3 condition 4
   is present.
5. The implementation materially exceeds the reviewed scope.
6. The slice touches a Tier 3 area that the review pack did not flag
   or that the agent did not identify during classification.
7. The agent cannot confidently classify the slice into a single tier.
8. Migration safety is unclear (locking, backfill size, concurrent
   writes).
9. Pre-commit hooks fail.

The stop-and-flag report cites the specific rule / ADR / file / line
that triggered the stop and proposes the smallest compliant next step.

#### 4.7.5 Carry-forward discipline

Every final report and every commit message body MUST enumerate
carry-forward items: work intentionally deferred, hardening notes not
yet addressed, TODOs planted in code. An item introduced in slice N
MUST appear in the carry-forward list of slice N+1 and is closed only
when explicitly addressed (implemented, rejected with reason, or
superseded by a new ADR). At each major phase boundary, the first
slice MUST reconcile the accumulated carry-forward list from the prior
phase.

#### 4.7.6 Commit message

Commit messages MUST follow **Conventional Commits**
(`type(scope): subject`), with an imperative subject ≤72 characters
and no trailing period. The body SHOULD cite the invariant(s) or
ADR(s) the change relates to. The body MUST include the tier
classification and carry-forward list per §§4.7.3 and 4.7.5.

#### 4.7.7 Post-commit report

After a successful commit, the agent MUST report:

- The commit hash.
- The branch name.
- The tier classification.
- The exact commit message.
- Any pre-commit hook output.
- The result of `git status --short` post-commit.

The detailed per-commit procedure lives in
[`.claude/skills/safe-local-commit.md`](./.claude/skills/safe-local-commit.md).
Agents MUST follow that skill step-by-step for every commit. Skipping
any step is a governance incident.

### 4.8 Remote and Destructive Git Operations

The operations listed below are gated per-tier by §4.7:

- **Tier 1**: `git push` (non-force, non-history-rewriting) is
  permitted without an approval phrase, subject to §§4.7.3–4.7.4.
- **Tier 2 and Tier 3**: `git push` is **prohibited** unless the human
  has explicitly said the phrase **"approved to push"** in the current
  session (paraphrases do NOT count). Single-use, scoped to the
  preceding turn.

The following operations are **prohibited at every tier** unless the
human has explicitly said the phrase **"approved to push"** in the
current session, AND the operation has been named or plainly implied
by the preceding turn:

- `git push --force`, `--force-with-lease`, or any force variant.
- `git commit --amend` on any commit.
- `git rebase` (interactive or non-interactive).
- `git merge` that creates a merge commit or alters shared branch history.
- `git tag` (create, move, or delete).
- `git branch -d`, `git branch -D`, or any branch deletion.
- `git reset --hard`, `git reset --keep`, or any history-rewriting
  reset.
- `git reflog expire`, `git gc --prune=now`, or any operation that
  discards recoverable history.

Force-pushing to `main` or any protected branch is prohibited **even
with** "approved to push". That operation REQUIRES a separate, written
authorization that names the branch and the reason.

Approval phrases are **single-use** and scoped to the specific
operation discussed in the preceding turn. They do not carry forward
to later operations in the same session. A new operation requires a
new approval.

---

## 5. Cost-Conscious Enterprise Posture

Medcore is built by a solo operator with AI assistance. The bar is
**enterprise quality at startup cost**. Agents MUST:

- Prefer managed, audited services over bespoke infrastructure.
- Prefer open standards (FHIR, OpenAPI, OIDC) over proprietary lock-in.
- Avoid premature abstraction. Three concrete implementations precede one
  abstraction.
- Flag any recommendation whose monthly run-rate exceeds trivial cost, with
  an estimate and at least one cheaper alternative.

---

## 6. Source of Truth & Precedence

When guidance conflicts, the following order applies (highest first):

1. Applicable law and regulation (HIPAA, HITECH, GDPR, 21 CFR Part 11).
2. This file (`AGENTS.md`).
3. `.cursor/rules/*.mdc` at the matching scope.
4. Approved ADRs in `docs/adr/`.
5. Documentation under `docs/`.
6. Existing code conventions.
7. User instructions in the current session.

An agent that cannot reconcile a conflict MUST halt and request clarification.

---

## 7. Change Control for This File

`AGENTS.md` itself is governed. Changes REQUIRE:

- An ADR describing the amendment and its rationale.
- Human approval from the repository owner.
- A commit message prefixed `governance:`.

Silent drift is a governance incident.
