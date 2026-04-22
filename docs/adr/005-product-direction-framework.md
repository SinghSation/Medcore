# ADR-005: Product direction framework and living-doc maintenance

- **Status:** Proposed
- **Date:** 2026-04-21
- **Authors:** Gurinder Singh
- **Reviewers:** Gurinder Singh (repository owner)
- **Supersedes:** none (extends `AGENTS.md` §§4.7.1, 4.7.3, 4.7.6)
- **Related:** `AGENTS.md` §§2, 4.7; `docs/architecture/medcore_architecture_charter.md`;
  ADR-001, ADR-002, ADR-003, ADR-004; `docs/project-timeline.md`

---

## 1. Context

Phases 0 through 3E have established Medcore's platform substrate:
identity (OIDC, JIT provisioning), tenancy (row-scoped with runtime RLS
enforcement), audit (append-only v1 plus a cryptographic v2 chain),
and a tiered governance model (ADR-004). The charter, `AGENTS.md`, and
four ADRs give the repository a durable operating contract for **how**
change is made.

What the repository does **not** yet have is a durable operating
contract for **what** Medcore becomes and **in what order**. The
consequences of that gap are already observable:

- Feature direction has been set inside session-scoped conversations.
  A decision made in session N ("DPC first") has no authoritative
  home; by session N+K, the decision can drift without any repository
  artifact noticing.
- Planning across sessions has relied on `docs/project-timeline.md`,
  which is correctly scoped as an **append-only historical narrative**
  and is the wrong place for forward-looking direction.
- There is no explicit **claim ledger** — a public statement of what
  Medcore does and does not claim about HIPAA, SOC 2, ONC, or other
  certifications. Absent that ledger, a future commit, README update,
  or sales-collateral copy can overstate posture and create legal
  exposure that no prior ADR has bounded.
- There is no formal niche commitment. Medcore is being built to
  eventually serve DPC, telehealth, clinics, and hospitals. Without a
  declared sequencing and per-niche scope, every feature decision has
  four possible "right answers" and no way to pick.
- The existing ADR surface (ADR-001 through ADR-004) covers
  architectural and governance decisions. It does not cover product
  strategy (niches, competitive positioning, workflow benchmarks,
  compliance maturity). These belong in a different kind of document
  with a different cadence: living docs reviewed on a schedule, not
  an immutable decision log.

External strategic review and architectural alignment (2026-04)
produced four binding inputs to this ADR:

1. **Niche order:** DPC → Telehealth → Clinics → Hospitals.
2. **ONC certification:** deferred until pilot traction exists;
   non-ONC-required niches are the starting addressable market.
3. **Time horizon:** first real pilot in 9–12 months; production-grade
   platform in 18–24 months.
4. **Claim ledger:** explicit, strict, and honest. Medcore publishes
   the list of claims it does **not** yet make (HIPAA-certified,
   SOC 2 attested, ONC-certified, HITRUST-certified) and updates that
   list only when the operational evidence exists.

A fifth, emergent input from the same review: Medcore's plans have
been backend-heavy. The **UX and clinician-workflow layer** — the
seconds per note, clicks per action, cognitive load — is the primary
competitive dimension against Elation, Healthie, Canvas, Atlas, and
others in the DPC / ambulatory niche. No prior ADR binds this as a
first-class concern; workflow targets appear nowhere in the repository.

This ADR closes those gaps.

## 2. Decision

**We will establish a six-document product-direction surface under
`docs/product/`, govern its lifecycle with living-doc review cadences,
require every code-bearing commit to cite the roadmap phase it advances,
and place all four strategic decisions above under the authority of this
ADR. `docs/product/` becomes a new governed directory: Tier 2 by default,
escalating to Tier 3 only for content changes that alter roadmap phases,
claim ledger, compliance stance, or Definition-of-Done thresholds.**

### 2.1 The six-document surface

`docs/product/` contains exactly these files. Each file carries
frontmatter with `Status`, `Last reviewed`, `Next review`, and
`Cadence` fields.

| File | Purpose | Cadence |
| ---- | ---- | ---- |
| `00-vision.md` | Mission, target niches, locked strategic decisions (§2.2), public claim ledger | Stable; reviewed yearly or on niche change |
| `01-competitive-landscape.md` | Per-niche competitor map (Epic, Oracle Health, athenahealth, eClinicalWorks, NextGen, Elation, Healthie, Canvas, Atlas, others); threat and opportunity analysis | Quarterly |
| `02-roadmap.md` | Phase 3F through 12+: entry/exit criteria, dependencies, explicit non-goals per phase | Living; reviewed per slice that affects a phase boundary |
| `03-definition-of-done.md` | Per-phase exit bar including measurable workflow benchmarks (create patient ≤ 10s, start encounter ≤ 3 clicks, complete basic visit note ≤ 90s, sign note ≤ 1 click, refill medication ≤ 15s) and the production-readiness checklist each phase inherits | Stable; amended when a phase closes or a benchmark changes |
| `04-compliance-and-legal.md` | Compliance maturity phases A → D, HIPAA scope, state privacy scope, BAA posture, clinical-safety governance timing, FDA SaMD boundary, insurance obligations, reference-data licensing obligations (headline only — details in 05) | Living; reviewed quarterly and on regulatory change |
| `05-interop-and-reference-data.md` | FHIR R4 + US Core + SMART App Launch 2.x + USCDI v3 surface plan; reference-data licensing (SNOMED CT, CPT, LOINC, RxNorm, ICD-10, NDC) | Living; reviewed per interop slice |
| `06-ux-and-workflow.md` | Workflow-first principles, design-system boundaries, accessibility baseline (WCAG 2.1 AA), usability-evidence cadence, competitor-benchmark methodology | Living; reviewed quarterly |

No seventh file is created under this ADR. Any additional product
doc requires a superseding or amending ADR.

### 2.2 Locked strategic decisions (binding inputs)

These decisions derive from external strategic review and
architectural alignment (2026-04). They are embedded in
`docs/product/00-vision.md` and referenced throughout the roadmap.
They move only via a superseding ADR.

1. **Niche order:** DPC is the first-pilot niche; Telehealth is the
   second; ambulatory clinics third; hospitals last. A **telehealth-lite
   accelerator** (Phase 5D) is permitted as an interstitial pilot path
   to shorten the distance to a first paying customer, without
   reclassifying telehealth as the primary niche.
2. **ONC certification:** not targeted prior to Phase 9. Early phases
   target niches that do not require ONC-certified status (DPC,
   cash-pay clinics, telehealth startups). Certification is revisited
   when product-market fit exists and a certified track materially
   unlocks revenue.
3. **Time horizon:** first real pilot 9–12 months from the ADR-005
   landing; production-grade platform 18–24 months. Slippage triggers
   a roadmap review, not a missed ship date.
4. **Claim ledger:** `00-vision.md` publishes a standing "we do NOT
   claim" section. Entries leave that section only when the
   operational evidence exists AND a superseding statement lands in
   `04-compliance-and-legal.md`. Agents MUST refuse to land sales
   collateral, README copy, or marketing text that contradicts the
   ledger.

### 2.3 Tier classification for `docs/product/`

`docs/product/**` is **Tier 2 by default**. A commit touching only
`docs/product/**` is autonomous-commit; push still requires
`"approved to push"` per the existing Tier 2 rules.

A `docs/product/**` change **escalates to Tier 3** when its diff
materially alters ANY of:

- Roadmap phase definitions: entry criteria, exit criteria, phase
  order, or the set of declared phases (`02-roadmap.md`).
- The public claim ledger: the "we do NOT claim" list in
  `00-vision.md` §claim-ledger, or any compliance-posture statement
  in `04-compliance-and-legal.md` that moves from "not claimed" to
  "claimed" or vice versa.
- Compliance stance: maturity-phase boundaries, regulatory scope,
  BAA posture, or the SaMD boundary (`04-compliance-and-legal.md`).
- Definition-of-Done thresholds: workflow benchmark targets, gate
  conditions, or the per-phase exit bar (`03-definition-of-done.md`).

Non-escalating edits — typo fixes, clarifying prose, cadence-driven
review refreshes, adding a competitor row to a landscape table,
updating the `Last reviewed` date — remain Tier 2.

Classification is declared in the commit body (see §2.5). When
ambiguous, the ADR-004 §2.1 tie-breaker applies: Tier 3.

### 2.4 Living-doc maintenance discipline

Each file in `docs/product/` carries a `Next review:` date in its
frontmatter. Agents MUST refuse to let a living doc drift silently
past its `Next review:` date. On the first slice after that date,
either:

- The agent refreshes the doc (updates or explicitly re-affirms
  content; bumps `Last reviewed:` and `Next review:`), OR
- The agent adds a **`Review-Deferred:`** line to the commit body
  citing the specific date the review is deferred to and the reason.

A slice MUST update the relevant `docs/product/` file in the same
commit when the slice materially changes any content named in §2.3
(roadmap phase, claim ledger, compliance stance, DoD threshold). If
the exact change is unclear and would block commit velocity, the
agent MUST instead insert a **`TODO(review)`** marker in the relevant
doc section naming the specific question, and cite the marker in the
commit body. **Documentation ambiguity MUST NOT block forward
progress.** The marker is a carry-forward item resolved by the next
slice that can answer it.

**Constraints on `TODO(review)` use (hard guardrails against silent
doc decay):**

1. A `TODO(review)` marker is permitted ONLY when BOTH:
   (a) the doc-update change impact is genuinely ambiguous
   (the agent cannot confidently decide what the doc should
   say), AND
   (b) the commit body contains a matching `Docs-Review-TODO:`
   trailer naming the specific file, section, and question.
   Inserting a `TODO(review)` without the trailer, or using the
   trailer without inserting the marker, is a governance incident.

2. **Hard cap: no more than 3 active `TODO(review)` markers per
   `docs/product/**` file at any time.** An "active" marker is any
   unresolved `TODO(review)` comment in the file. Resolution means
   the marker is removed and the underlying question is answered in
   the file (content written, or the question declared moot with a
   one-line rationale in the slice that removes the marker). A slice
   that would push a file to a 4th active marker MUST halt under
   ADR-004 §2.3 guardrail 3 (rule conflict) and EITHER resolve an
   existing marker in the same slice OR re-scope to avoid adding a
   new one.

3. Every slice's pre-commit review pack MUST include the current
   active-`TODO(review)`-count per affected `docs/product/**` file so
   the cap is visible to the human reviewer.

4. Resolving a `TODO(review)` marker is carry-forward work; the
   ledger in the commit body tracks each marker from insertion
   through resolution.

These constraints exist because the escape hatch is the single
mechanism by which the living-doc discipline could erode silently.
The cap, the required trailer pairing, and the visible count make
erosion a stop-and-flag event, not a drift.

### 2.5 Commit-body requirements (extends AGENTS.md §4.7.6)

Every code-bearing commit MUST include, in the body, exactly one
`Roadmap-Phase:` trailer naming the phase the slice advances. Format:

```
Roadmap-Phase: <phase> (<short description>)
```

Examples:

```
Roadmap-Phase: 3F (observability spine)
Roadmap-Phase: 4A (patient registry)
Roadmap-Phase: governance (ADR-005 — product direction framework)
```

Constraints:

- **Exactly one** `Roadmap-Phase:` line per commit. Ranges
  (`3F-3G`), lists (`3F, 4A`), or "multi-phase" classifications are
  prohibited. A slice that genuinely advances two phases is two
  slices and two commits.
- The phase identifier MUST match a row in `docs/product/02-roadmap.md`
  OR be the literal token `governance` (for ADR / rule / AGENTS.md
  changes that do not advance a code phase).
- The trailer appears in commit bodies **from ADR-005's landing
  commit forward**. No retroactive rewrite of prior commits is
  required or permitted.
- Docs-only commits to `docs/project-timeline.md` use
  `Roadmap-Phase: docs` as the canonical token, reflecting that
  append-only historical narrative is not a code phase.

**Reserved scope of the `governance` token.** The `governance`
token is permitted ONLY for commits whose diff is wholly contained
within:

- `docs/adr/**`
- `AGENTS.md`
- `.cursor/rules/**`
- `.claude/**`
- `docs/architecture/**`
- `docs/product/**`

A commit that touches feature code, platform code, database
migrations, tests (except governance-adjacent test infrastructure
that was itself classified as Tier 3), infrastructure, or any
other non-governance path MUST cite a numbered roadmap phase.
Using `governance` to label a slice that advances feature or
platform work is a governance incident under ADR-004 §2.3 universal
guardrails.

**Reserved scope of the `docs` token.** The `docs` token is
permitted ONLY for commits whose diff is wholly contained within
`docs/project-timeline.md`. Any commit that also touches code,
migrations, or governance files MUST use a roadmap-phase
identifier or `governance`, not `docs`.

Misuse of either reserved token halts the commit per ADR-004
§2.3 guardrail 7 (classification ambiguity / mis-classification)
and requires the agent to re-scope the slice or split it.

### 2.6 Repository structure amendment

`AGENTS.md` §2 is amended to list `docs/product/` in the frozen
top-level structure with the description *"product direction:
vision, roadmap, definition of done, compliance maturity, interop
strategy, UX and workflow"*. No other structural change is made.

### 2.7 Rule file

A new `.cursor/rules/08-product-docs-maintenance.mdc` codifies §§2.1,
2.3, 2.4, and 2.5 as a cursor-enforceable rule. It is a normative
companion to this ADR; in conflicts, the ADR wins.

## 3. Alternatives Considered

### 3.1 One consolidated roadmap doc
**What:** A single `docs/product/roadmap.md` file containing vision,
roadmap, DoD, compliance, interop, and UX sections.
**Why rejected:** The six concerns have different cadences (vision
is yearly; competitive landscape is quarterly; roadmap is per-slice;
compliance is event-driven). A single file forces a single cadence,
which causes the less-frequently-updated sections to ossify and the
more-frequently-updated ones to churn the rest of the file during
routine diffs. Reconsider if the product surface collapses to a
single niche with a single compliance posture — which it will not.

### 3.2 Fold product direction into the architecture charter
**What:** Add roadmap, niches, and compliance maturity to
`docs/architecture/medcore_architecture_charter.md`.
**Why rejected:** The charter is an **invariants** document — Section
2 of the charter explicitly names its contents as "invariants. They
bind every component." Product direction is not an invariant; it
evolves. Mixing evolutionary content into an invariants doc
degrades both. Reconsider only if the charter is itself
reclassified as a living doc, which would require its own ADR.

### 3.3 No enforcement mechanism — direction as conversation
**What:** Accept that product direction lives in session
conversations and doc-update discipline stays implicit.
**Why rejected:** Proven failure mode across the first 14 commits.
Every session starts without a shared forward-looking artifact,
forcing rediscovery of direction. A false claim in a README, commit
body, or sales artifact is legally actionable under FTC §5 (unfair
or deceptive acts) even absent malice. The claim ledger is a
compliance control, not a comfort. Reconsider never; this has
already been lived.

### 3.4 Promote the entire `docs/product/` tree to Tier 3
**What:** Any change to any `docs/product/**` file requires the
Tier 3 phrase regime.
**Why rejected:** Would suppress routine hygiene edits (typo fixes,
clarifications, review-date bumps, competitor-row additions). The
human-authority moment is worth preserving for governance-bearing
changes (roadmap phases, claim ledger, compliance stance, DoD
thresholds) but is overhead for everything else. The content-based
escalation model in §2.3 preserves the Tier 3 protection exactly
where it matters and releases it where it does not. Reconsider if
an incident demonstrates that content-based classification is
error-prone in practice — in which case amend to the stricter model
by superseding ADR.

### 3.5 Multi-phase `Roadmap-Phase:` trailers
**What:** Permit `Roadmap-Phase: 3F, 4A` or `Roadmap-Phase: 3F-3H`
for slices that advance multiple phases.
**Why rejected:** Ambiguity in the commit log defeats the purpose.
A slice that touches multiple phases is almost always a slice that
should be split. The single-phase rule is a forcing function for
scope discipline. Reconsider never without a compensating control
(e.g., machine-parseable phase-graph with explicit multi-phase
grammar) of equivalent strength.

## 4. Consequences

### 4.1 Positive
- Product direction becomes an artifact, not a memory. A new agent,
  a new reviewer, or a future human owner can reconstruct the
  current product posture from `docs/product/` without replaying
  session history.
- The claim ledger is a standing compliance control. Every future
  marketing claim is implicitly gated by it.
- Workflow benchmarks become first-class quality gates. Phase 4
  slices cannot close without meeting their DoD workflow threshold,
  aligning engineering output with the competitive dimension that
  determines EHR adoption.
- `Roadmap-Phase:` trailers make the commit log machine-parseable
  for future analytics (velocity per phase, carry-forward
  resolution time, phase-closure trends).
- Tier 2 default for `docs/product/` preserves the audit-moment
  where it matters (governance-bearing content changes) while
  eliminating friction for routine maintenance. The content-based
  escalation to Tier 3 scales the authority discipline to risk.
- Living-doc discipline (review cadences, deferred-review markers)
  prevents silent decay of forward-looking content.

### 4.2 Negative
- Every code-bearing commit now requires a `Roadmap-Phase:` trailer.
  Additional ~20 seconds of cognitive overhead per commit.
- Six living docs require ongoing maintenance. Without discipline
  they rot into another governance surface that contradicts code —
  the failure mode §4.3 risks describe.
- Tier classification gains a content-based dimension for
  `docs/product/`. Classification errors are now possible where
  before there were none.
- The `Next review:` date mechanism adds a soft deadline to every
  living doc. Missing it produces process chatter (a Review-Deferred
  marker in a commit that otherwise had nothing to do with review
  cadence).

### 4.3 Risks & Mitigations
- **Risk:** Living docs decay — content becomes stale, `Next review:`
  dates slip, the claim ledger drifts from reality.
  **Mitigation:** §2.4 requires either refresh or an explicit
  `Review-Deferred:` line. Missed review dates surface in commit
  bodies, not in silence. Periodic audits (quarterly) verify the
  ledger matches actual operational posture.

- **Risk:** A content change to `docs/product/` is mis-classified as
  Tier 2 when it is governance-bearing (e.g., a claim-ledger change
  slipped through as a "typo").
  **Mitigation:** Rule 08's pre-commit checklist requires the agent
  to enumerate the sections touched and match them against the §2.3
  escalation triggers. Universal guardrail ADR-004 §2.3.7 (can't
  classify → Tier 3) applies. Quarterly review reconciles the claim
  ledger against actual compliance posture.

- **Risk:** `Roadmap-Phase:` becomes lint rather than signal —
  agents stuff the same value into every commit without engagement.
  **Mitigation:** The phase identifier MUST match a roadmap row or
  the token `governance`. An unknown identifier is a stop-and-flag
  per universal guardrails. Review pack format requires the agent
  to name the phase AND cite the roadmap row; misuse is visible to
  the human in the review pack before commit.

- **Risk:** The six-doc surface grows informally — a seventh file
  appears ("07-something.md") because an agent needed somewhere to
  put content.
  **Mitigation:** §2.1 declares the set as closed. New files require
  a superseding or amending ADR, same protection that locked the
  top-level directory structure under `AGENTS.md` §2.

- **Risk:** External strategic review changes its mind in six
  months; the locked decisions in §2.2 become wrong.
  **Mitigation:** §2.2 explicitly binds the decisions only until
  superseded by a later ADR. Changes of mind produce ADR-00N that
  supersedes this one. The decisions do not decay silently.

- **Risk:** Content-based tier escalation is error-prone in practice —
  agent drift in classification over many slices.
  **Mitigation:** Quarterly governance review (new ops item) samples
  20 commits and verifies classifications. If error rate exceeds
  10%, amending ADR promotes `docs/product/**` to full Tier 3 per
  §3.4 rejection's reconsideration clause.

## 5. Compliance & Security Impact

- **HIPAA §164.530(i) (policies and procedures):** the claim ledger
  becomes a repository-native policy statement. Its discipline
  (what we do and do not claim) maps to the §164.530 requirement
  for written, current, maintained policies.
- **HITECH / HIPAA Breach Notification:** `04-compliance-and-legal.md`
  will name the 60-day notification clock as an operational
  obligation and cite the incident-response runbook that implements
  it. Gating phases (A → D) make the maturity state explicit, which
  supports an honest breach-notification posture.
- **SOC 2 CC2.1 (information & communication):** the `docs/product/`
  surface is a structured communication of objectives and scope.
  Required by CC2.1 for any Type II attestation.
- **SOC 2 CC5.3 (control activities / policies):** the claim ledger
  and maturity phases are policies documented and approved per
  CC5.3.
- **SOC 2 CC8.1 (change management):** the `Roadmap-Phase:` trailer
  adds explicit traceability from commit to product objective,
  supporting CC8.1's "changes are authorized" requirement.
- **FTC §5 (unfair or deceptive acts):** the claim ledger is a
  standing control against overclaiming compliance or capability
  in any repo-resident text (README, sales copy in docs, release
  notes). A false claim is actionable regardless of intent; this
  ADR institutes the control that prevents inadvertent violations.
- **FDA Software-as-Medical-Device (21 CFR 820 / 21 CFR 11):**
  `04-compliance-and-legal.md` names the SaMD boundary. Current
  decision: no CDS claims, no diagnostic recommendations, no
  dose calculators until Phase 7+. The boundary is a standing
  control; crossing it requires a superseding ADR and a clinical
  safety case document.
- **State privacy law:** `04-compliance-and-legal.md` names the
  material state laws Medcore is scoped to (Washington My Health
  My Data Act, California CPRA, and others emerging). Not
  operationally binding at Phase 3 but documented so Phase 6+
  implementation is scoped, not invented.
- **PHI handling:** no direct impact. This ADR changes no data
  path. It is a governance ADR.

## 6. Operational Impact

- **Developer experience:** +1 trailer line per commit; 6 files to
  keep alive; quarterly governance review. Negligible after muscle
  memory forms; measurable as habit for the first ~5 slices.
- **Commit log:** the `Roadmap-Phase:` trailer becomes grep-able
  metadata. Enables phase-scoped velocity queries without bespoke
  tooling.
- **Review load:** +1 section per review pack naming the phase and
  citing the roadmap row.
- **CI load:** none in this ADR. CI enforcement of the trailer
  requirement and `Next review:` date checks is deferred to Phase
  3I per §7 (rollout plan). Until CI enforces, ADR-004 §2.3
  universal guardrails apply to the agent directly.
- **Cost:** zero direct cost. Indirect cost = time to maintain six
  living docs at their stated cadences.
- **Runbook load:** no change. Existing runbooks do not reference
  `docs/product/`.

## 7. Rollout Plan

1. **Single atomic commit lands this ADR plus:**
   - `AGENTS.md` §2 amendment (add `docs/product/` to structure);
     §4.7.1 amendment (add `docs/product/**` tier classification);
     §4.7.3 amendment (add `Roadmap-Phase:` trailer to universal
     preconditions); §4.7.6 amendment (add trailer format);
     new §4.7.8 (living-doc maintenance clause with docs-must-not-
     block-commits escape).
   - `.cursor/rules/08-product-docs-maintenance.mdc`.
   - `docs/product/00-vision.md`, `02-roadmap.md`,
     `04-compliance-and-legal.md` — fully populated.
   - `docs/product/01-competitive-landscape.md`,
     `03-definition-of-done.md`, `05-interop-and-reference-data.md`,
     `06-ux-and-workflow.md` — skeletons with frontmatter and
     section scaffolds; each carries a `Status: Draft` marker and a
     `Next review:` date two weeks out.
   - This commit itself is **Tier 3** (touches `AGENTS.md`, adds
     an ADR, adds a rule file, adds the new `docs/product/`
     directory under the §2 freeze). It lands under the existing
     ADR-004 phrase regime with the per-change approval naming
     governance files.
2. **Follow-up Tier 2 slice** fills the four skeletons (01, 03, 05,
   06) with full content. Splits permitted by size.
3. **Subsequent slices (from Phase 3F forward)** operate under the
   `Roadmap-Phase:` trailer requirement. Verification: the first
   Phase 3F commit body visibly shows `Roadmap-Phase: 3F (observability
   spine)` and cites the corresponding row in
   `docs/product/02-roadmap.md`.
4. **Quarterly governance review (first occurrence 2026-07-21)**
   samples the prior quarter's commits for classification errors,
   refreshes the competitive landscape, reconciles the claim ledger
   against operational evidence.
5. **CI enforcement (Phase 3I)** adds a machine check that fails
   any PR missing the `Roadmap-Phase:` trailer or introducing a
   living-doc `Next review:` date slippage without a
   `Review-Deferred:` line. Until Phase 3I, enforcement is agent-
   and-human, via ADR-004 §2.3.
6. **Rollback plan:** a superseding ADR removes the
   `Roadmap-Phase:` trailer requirement, deprecates
   `docs/product/`, and reverts the `AGENTS.md` amendments. The
   `docs/product/` tree becomes a historical artifact (renamed to
   `docs/product-archive/` or deleted via explicit ADR). No code
   is affected.

## 8. Acceptance Criteria

- [ ] ADR-005 file lands under `docs/adr/` with Status: Proposed.
- [ ] `AGENTS.md` §§2, 4.7.1, 4.7.3, 4.7.6 amended; new §4.7.8 added.
- [ ] `.cursor/rules/08-product-docs-maintenance.mdc` lands.
- [ ] `docs/product/00-vision.md`, `02-roadmap.md`,
      `04-compliance-and-legal.md` fully populated.
- [ ] `docs/product/01-*.md`, `03-*.md`, `05-*.md`, `06-*.md`
      skeletons with frontmatter and section scaffolds.
- [ ] Commit body carries `Tier: 3` and
      `Roadmap-Phase: governance (ADR-005 — product direction framework)`.
- [ ] Commit is Conventional-Commits-prefixed with `governance:`.
- [ ] Owner approval recorded via the explicit Tier 3 phrase regime
      naming governance files.
- [ ] First post-merge code slice (Phase 3F) lands with a valid
      `Roadmap-Phase:` trailer matching a roadmap row.

## 9. References

- `AGENTS.md` §§2, 4.1, 4.6, 4.7, 6, 7.
- `docs/architecture/medcore_architecture_charter.md`.
- ADR-001 (Postgres + Flyway + row-level tenancy).
- ADR-002 (OIDC-only authentication).
- ADR-003 (Audit v1: append-only, synchronous, DB-immutable).
- ADR-004 (Tiered execution authority model).
- `docs/project-timeline.md` (append-only historical narrative).
- External strategic review and architectural alignment (2026-04).
- HIPAA §§164.308, 164.312(b), 164.530(i).
- SOC 2 CC2.1, CC5.3, CC8.1.
- FTC Act §5.
- FDA 21 CFR Part 820 (SaMD boundary awareness).
- ONC HTI-1 final rule (USCDI v3 baseline effective 2026-01-01).
