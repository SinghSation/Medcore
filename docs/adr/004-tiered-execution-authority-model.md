# ADR-004: Tiered execution authority model for agent commits and pushes

- **Status:** Proposed
- **Date:** 2026-04-21
- **Authors:** Gurinder Singh
- **Reviewers:** Gurinder Singh (repository owner)
- **Supersedes:** none (amends `AGENTS.md` §§4.7–4.8)
- **Related:** `AGENTS.md` §§4.1, 4.6, 4.7, 4.8, 6, 7;
  `.claude/skills/safe-local-commit.md`; `.cursor/rules/00-global-architecture.mdc`

---

## 1. Context

The current `AGENTS.md` §§4.7–4.8 require the human to utter the exact
phrase **"approved to commit"** before every local commit, and the exact
phrase **"approved to push"** before every push. Approval is declared
**single-use** and scoped to the immediately preceding turn. Paraphrases
do not count. High-risk areas (authentication, authorization, audit
logging, migrations, infrastructure, dependency changes, encryption /
secrets, PHI paths, FHIR contracts, governance files) additionally
require an explicit per-change approval naming the area.

After four governed slices executed under this regime (3A.2 persistence,
3A.2-followup Testcontainers, 3A.3 identity, 3B.1 tenancy, 3C audit
v1 — 94/94 tests across the pack, zero governance incidents), the
regime's cost / value is observable:

- The phrase discipline **succeeds** at keeping risky change (auth, DB
  migrations, governance files) under a clear, auditable authority
  moment. Every high-risk commit so far has been paired with an
  explicit human statement in the session log.
- The phrase discipline **also** applies uniformly to low-risk changes
  (docs-only edits, test infrastructure touch-ups, trivial config
  updates). For those slices the cost of the per-operation phrase is
  disproportionate to the governance value — it creates friction
  without strengthening any invariant.

At the same time, uniformly dropping the phrase requirement would
weaken the exact protection that makes §4.7 load-bearing: the explicit
authority moment for changes to auth, audit, governance, or DB state.
"Assume approval unless the human says stop" is a materially different
posture from "require approval for each operation" and has very
different audit-trail properties. Under HIPAA §164.312(b) (audit
controls) and SOC 2 CC6.1 (logical access), the repository wants the
strong form for sensitive change and the weak form only for demonstrably
safe change.

No alternative tiered model is currently defined; every slice is
treated identically. This ADR introduces a calibrated model that
preserves the strong form where it matters and permits autonomy where
it is safe.

## 2. Decision

**We will replace the uniform per-operation phrase regime in
`AGENTS.md` §§4.7–4.8 with a tiered execution authority model that
classifies each slice as Tier 1, Tier 2, or Tier 3, and applies
proportionate approval requirements. Tier 3 preserves the existing
phrase-based regime; Tier 1 permits autonomous commit and push
subject to hard guardrails; Tier 2 sits between them.**

Specifics:

### 2.1 Tier definitions

**Classification tie-breaker (applies to every slice).**
If a slice spans multiple tiers, or if the agent cannot unambiguously
assign a single tier, **the highest tier touched applies to the
entire slice**. Mixed-tier slices are never classified down. Example:
a predominantly docs-only change that also amends a single line of
`AGENTS.md` is Tier 3, not Tier 1. This rule exists so a single
Tier 3 file cannot silently ride alongside benign changes.

**Tier 3 — High-risk (phrase-gated; legacy regime preserved).**
A slice is Tier 3 if its diff touches ANY of the following:

- Authentication.
- Authorization.
- Audit logging (code paths or emission sites).
- Database migrations that are **not** strictly additive-and-idempotent
  as defined by Rule 03 §"Safety gates" (drops, in-place renames,
  NOT NULL on populated columns, column-type narrowing, unique on
  populated columns, non-trivial backfills, locking-budget-exceeding
  operations).
- Infrastructure / Terraform (`infra/**`).
- Dependency manifests or lockfiles with material transitive changes.
- Encryption, key management, secrets handling.
- PHI-handling code paths.
- Interoperability / FHIR contracts (`packages/schemas/fhir/**`).
- Governance files (`AGENTS.md`, `.cursor/rules/**`, `.claude/**`,
  `docs/adr/**`, `docs/architecture/**`).
- Any area the agent is not confident classifying.

Tier 3 requires, in the current session:

1. The exact phrase **"approved to commit"**.
2. An explicit per-change approval naming the high-risk area(s).
3. The exact phrase **"approved to push"** before any push.

All three remain **single-use and scoped to the preceding turn** per
the existing §4.8 language.

**Tier 2 — Medium-risk (commit autonomous; push phrase-gated).**
A slice is Tier 2 if it is not Tier 3 AND its diff touches ANY of:

- **Purely additive Flyway migrations**, defined strictly as a
  migration that matches ALL of the following AND NONE of the
  Tier 3 migration triggers listed under §2.1 "Tier 3":
  - Allowed operations: `CREATE TABLE` of a new table; `ADD COLUMN`
    where the new column is NULL-allowed with no default;
    `CREATE INDEX` / `CREATE INDEX IF NOT EXISTS` on a column; role
    and grant operations that only EXPAND no-privilege defaults;
    `CREATE TYPE` / `CREATE SCHEMA` for new names.
  - Forbidden operations (any one forces Tier 3): `DROP` of any
    kind; `RENAME` in place; `ALTER COLUMN` (type change, SET/DROP
    DEFAULT, SET/DROP NOT NULL); constraint addition on an existing
    populated table (CHECK, UNIQUE, FK, NOT NULL); data backfill of
    any size; `TRUNCATE`; any `REVOKE` that reduces an existing
    grant; any explicit transaction-timing or locking directive
    (`LOCK`, `SET lock_timeout`, etc.).
  - If ambiguous — Tier 3 (per the tie-breaker above).
- New API contract files under `packages/schemas/openapi/**` (not FHIR).
- New endpoints, controllers, or service classes outside the Tier 3
  areas above AND outside the identity / tenancy / audit modules'
  auth-sensitive code paths.
- Test infrastructure that materially affects the test harness's
  production-posture guarantees (e.g., `TestcontainersConfiguration`,
  new `@TestConfiguration` that replaces a security-layer bean).
- Developer runbooks touching security-adjacent topics (auth flow,
  audit role, secret handling — where the runbook is not itself a
  governance file).

A slice whose diff includes an additive migration AND any Tier 3
trigger is Tier 3 by the tie-breaker. Classifying such a slice as
Tier 2 on the basis of the migration alone is prohibited.

Tier 2 requires:

1. The agent commits autonomously (no phrase) ONLY after producing a
   review pack that enumerates files, validations run, and
   carry-forward items, AND all universal guardrails in §2.3 are
   satisfied.
2. The exact phrase **"approved to push"** remains required before
   any push (single-use, scoped to the preceding turn). Remote
   exposure preserves the strong authority moment.

**Tier 1 — Safe (full autonomy).**
A slice is Tier 1 if none of the Tier 2 or Tier 3 criteria apply AND:

- No database migrations of any kind.
- No governance files.
- No code in `platform/security/**`, `platform/audit/**`, or any
  feature module's auth/authz/audit emission sites.
- No dependency manifest or lockfile changes.
- No FHIR or other interoperability contracts.
- All required validations green (`./gradlew test` for backend;
  `pnpm test` / `pnpm typecheck` for frontend where applicable).

Tier 1 permits autonomous commit AND push in one continuous pass,
provided the universal guardrails in §2.3 are satisfied and the final
report carries an explicit tier classification, validations summary,
and carry-forward list.

### 2.2 Override phrases (human-issued, halt or escalate)

Any of the following, issued by the human in the current session,
override the default tier and either halt the slice or force Tier 3
treatment:

- **"hold"** — pause all work on the current slice.
- **"review only"** / **"draft only"** — prepare the slice, produce the
  review pack, do not commit, do not push.
- **"do not commit"** — as named; produce review pack only.
- **"do not push"** — as named; may commit, must not push.
- **"approved to commit"** / **"approved to push"** — remain valid as
  explicit Tier 3 authority and always supersede tier autonomy.

### 2.3 Universal guardrails (all tiers)

Agents MUST halt, produce a stop-and-flag report, and await human
direction — regardless of tier — if ANY of the following is true at
the time commit would otherwise occur:

1. Any required test is failing.
2. The diff appears to conflict with an accepted ADR.
3. The diff appears to conflict with `AGENTS.md` or any file under
   `.cursor/rules/**`.
4. Secrets, PHI, `.env` files, build artifacts, OS junk, or any other
   forbidden pattern per `AGENTS.md` §4.7 condition 5 are present.
5. The implementation materially exceeds the reviewed scope.
6. The slice touches a Tier 3 area that the review pack did not
   flag, or that the agent did not identify during classification.
7. The agent cannot confidently classify the slice into a single tier.
8. Migration safety is unclear for any reason (locking behavior,
   backfill size, concurrent write patterns).
9. Pre-commit hooks fail (§4.7 condition — unchanged).

The stop-and-flag report cites the specific rule / ADR / file / line
that triggered the stop and proposes the smallest compliant next step.

### 2.4 Carry-forward discipline (all tiers)

Every final report and every commit message body MUST enumerate
carry-forward items: work intentionally deferred, hardening notes not
yet addressed, TODOs planted in code. An item introduced as
carry-forward in slice N MUST appear in the carry-forward list of
slice N+1 and is closed only when explicitly addressed (implemented,
rejected with reason, or superseded by a new ADR).

At the entry to each major phase (e.g., 3D → 4A), the first slice
MUST reconcile the accumulated carry-forward list from the prior
phase and explicitly close, carry, or reject each item.

### 2.5 Per-action authority (unchanged)

This ADR does NOT modify `AGENTS.md` §4.6. Its list of actions that
require explicit in-turn human authorization (destructive commands,
dependency changes, disabling hooks/linters/tests, etc.) continues to
apply orthogonally to commit/push authority. A Tier 1 slice still
cannot, for example, `rm -rf` a directory on the agent's own
initiative; it still cannot install a new dependency without
explicit authorization in the same turn.

## 3. Alternatives Considered

### 3.1 Preserve the uniform phrase regime
**What:** Keep `AGENTS.md` §§4.7–4.8 unchanged; continue requiring
"approved to commit" / "approved to push" on every operation.
**Why rejected:** Proven friction on low-risk slices across four
completed phases. The regime makes no distinction between docs-only
edits and PHI-path changes, so the human ends up typing the same
phrase for incomparable risks. Reconsider if an incident demonstrates
that tier mis-classification can result in unauthorized change — in
which case roll back to this regime via superseding ADR.

### 3.2 Full auto-pilot (commit + push unless human halts)
**What:** Treat every slice as Tier 1 by default; rely on stop-phrases
("hold", "do not commit") to block action.
**Why rejected:** Breaks the audit-trail property that makes §4.7
load-bearing under HIPAA §164.312(b) and SOC 2 CC6.1. There would be
no explicit human-authority moment for an auth-code change, for
example. Silent drift of "what did the human agree to" is exactly
the governance incident §7 of `AGENTS.md` prohibits. Never
reconsider without a compensating control (e.g., multi-reviewer
gate, separate approver role) of equivalent strength.

### 3.3 Combined phrase ("approved to commit and push")
**What:** Keep the per-operation regime but permit a single compound
phrase to authorize both operations in one utterance.
**Why rejected:** The current §4.8 explicitly says approvals are
**single-use and scoped to the specific operation discussed in the
preceding turn**. A compound phrase bypasses the per-operation
boundary that makes the rule predictable. Minor time savings, real
interpretive ambiguity, no risk-proportionality benefit.

### 3.4 Numeric risk score instead of discrete tiers
**What:** Assign each slice a 1–10 score; approval requirements scale
continuously.
**Why rejected:** Continuous risk scoring has no natural review
ceremony. Discrete tiers map cleanly to discrete human behaviors
("this needs a phrase", "this goes through autonomously"). Revisit
if compliance / audit tooling ever demands a numeric score.

## 4. Consequences

### 4.1 Positive
- Tier 3 protections for authentication, audit, governance, and
  dangerous migrations are **preserved exactly**. The highest-risk
  paths keep their explicit human authority moment and named
  per-change approval.
- Tier 1 eliminates friction for safe slices (docs, test tweaks,
  runbooks, trivial refactors). The human's cognitive cost maps to
  risk, not to every operation.
- Tier 2's split (autonomous commit, phrase-gated push) preserves the
  remote-exposure firewall while letting local checkpointing proceed.
  A Tier 2 commit is recoverable locally; a Tier 2 push is not.
- Carry-forward discipline is now codified in governance rather than
  surviving on convention; items cannot silently disappear between
  slices.
- Override phrases give the human a reliable single-word escape to
  force Tier 3 or halt entirely, no matter how the agent classified
  the slice.

### 4.2 Negative
- The agent must classify every slice before committing. Mis-classification
  is the primary new failure mode (see §4.3 below).
- A reviewer of the commit log can no longer assume that every commit
  was paired with an explicit "approved to commit" utterance in the
  session — Tier 1 commits are autonomous. They are instead paired
  with a review pack and a classification rationale in the commit
  body. Audit tooling and future runbooks must understand this.
- The human is surrendering pre-commit visibility for Tier 1 slices in
  exchange for speed. Post-commit review (via `git log`, PR review,
  etc.) replaces pre-commit gating for those slices only.

### 4.3 Risks & Mitigations

- **Risk:** Agent mis-classifies a Tier 3 slice as Tier 1 or 2 and
  commits without a phrase.
  **Mitigation:** Universal guardrail §2.3.6 requires the agent to halt
  if any Tier 3 area is touched but not flagged. The Tier 3 list in
  §2.1 is explicit and file-path-scoped (e.g., "any file under
  `platform/security/**`"). When classification is ambiguous, §2.3.7
  forces Tier 3 by default.

- **Risk:** Agent autonomously pushes a Tier 1 slice that later proves
  to contain a subtle auth regression.
  **Mitigation:** Tier 1's definition explicitly excludes any code
  under `platform/security/**`, `platform/audit/**`, identity, or
  tenancy auth-sensitive paths. A slice touching those files cannot
  be Tier 1 at all. Additionally, every tier's final report must
  name the files changed so post-commit review can detect
  mis-classification quickly.

- **Risk:** Governance drift — the tier model is amended informally,
  reverting to "everything Tier 1".
  **Mitigation:** This ADR is governed by `AGENTS.md` §7. Any
  amendment requires a superseding ADR, a `governance:` commit, and
  owner approval — the same protections as the original document.

- **Risk:** Carry-forward items decay across phases as the list grows.
  **Mitigation:** §2.4 requires explicit reconciliation at each phase
  boundary. Items must be closed, carried, or rejected — no silent
  drop.

- **Risk:** A reviewer auditing commits expects per-operation phrase
  evidence and cannot find it for Tier 1 / 2 commits.
  **Mitigation:** Commit body template (added to
  `.claude/skills/safe-local-commit.md`) requires tier classification
  to appear in the commit body. An auditor reading the commit log
  sees either the phrase-paired commit (Tier 3) or an explicit tier
  classification with validation summary (Tier 1/2).

## 5. Compliance & Security Impact

- **HIPAA §164.312(b) (audit controls):** unchanged for audit-relevant
  paths — audit logging code remains Tier 3 and continues to require
  an explicit human-authority moment per commit.
- **HIPAA §164.308(a)(4) (access management) / SOC 2 CC6.1:** auth and
  authz code remain Tier 3. No change in the authority discipline for
  credential / session / token handling.
- **SOC 2 CC8.1 (change management):** the model strengthens change
  management for high-risk change (carry-forward discipline,
  stop-and-flag guardrails) while explicitly permitting proportionate
  autonomy for low-risk change. Both conditions are documented in
  this ADR and will appear in `docs/compliance/` when that index
  lands.
- **21 CFR Part 11:** where applicable, signing records for
  regulated change still require explicit human action. The Tier 3
  classification captures every current regulated-change surface;
  new regulated surfaces added in future phases MUST be added to the
  Tier 3 list via superseding or amending ADR.
- **Data residency / retention:** no direct impact.
- **Secret handling:** unchanged. Rule 01's secret-in-repo prohibition
  applies at all tiers; the universal guardrails block any commit
  containing secret patterns regardless of tier.

## 6. Operational Impact

- **Developer experience:** Tier 1 slices flow through without
  per-operation phrases. Tier 2 slices still require a single "approved
  to push" phrase. Tier 3 slices are unchanged.
- **Commit log readability:** commit bodies gain a mandatory "Tier:
  N" line plus the existing carry-forward list. A reviewer can triage
  risk at-a-glance.
- **On-call / incident:** no change. Incident response procedures do
  not rely on the tier model.
- **Runbook load:** `.claude/skills/safe-local-commit.md` is amended in
  the same commit as this ADR to match the new flow. No other runbook
  changes required.
- **Cost:** zero direct cost. Time savings on low-risk slices are the
  expected operational benefit.

## 7. Rollout Plan

1. Land this ADR and the corresponding `AGENTS.md` §§4.7–4.8
   amendment and `.claude/skills/safe-local-commit.md` update in a
   single `governance:` commit. That commit itself is Tier 3 (touches
   governance files) and therefore lands under the **existing**
   regime — explicit "approved to commit" with governance-files named
   as the high-risk area, then "approved to push".
2. From the commit AFTER the governance commit, the tier model is
   live. Every subsequent slice produces a review pack that includes a
   tier classification.
3. Verification:
   - The governance commit compiles (`make check-governance`) and the
     full backend test pack remains green post-merge.
   - At least one subsequent slice lands as Tier 1 AND at least one as
     Tier 3, and both commit bodies show the classification rationale
     visibly. (Observational acceptance — the new regime is exercised
     in real traffic, not just declared.)
4. Rollback plan: a superseding ADR reverts §§4.7–4.8 to the
   uniform-phrase regime, re-emits the prior text into `AGENTS.md`,
   and commits under `governance:`. No data loss. Any slices that
   committed under the intermediate tier model remain valid (their
   commit bodies still record their justifications).

## 8. Acceptance Criteria

- [ ] ADR-004 file lands under `docs/adr/`.
- [ ] `AGENTS.md` §§4.7–4.8 amended to point at this ADR and carry
      the tier summary.
- [ ] `.claude/skills/safe-local-commit.md` amended to reflect the tier
      flow.
- [ ] Commit message prefixed `governance:` per `AGENTS.md` §7.
- [ ] Owner approval (by the repository owner) recorded via the
      explicit in-session phrases (this is a Tier 3 slice under the
      existing regime).
- [ ] First post-merge slice produces a review pack including an
      explicit tier classification and carry-forward reconciliation.

## 9. References

- `AGENTS.md` §§4.1, 4.6, 4.7, 4.8, 6, 7.
- `.claude/skills/safe-local-commit.md`.
- `.cursor/rules/00-global-architecture.mdc` §"Git commit and push authority".
- ADR-001, ADR-002, ADR-003 (prior governed slices exercising §§4.7–4.8).
- HIPAA §164.312(b), §164.308(a)(4).
- SOC 2 CC6.1, CC8.1.
