---
status: Draft
last_reviewed: 2026-04-21
next_review: 2026-05-05
cadence: stable-amended-on-phase-close
owner: Repository owner
---

# Medcore — Definition of Done

> Per-phase exit bar. A phase does not close because "work feels
> complete" — it closes when the per-phase DoD is met and a slice
> documents closure.
>
> Governed by [ADR-005](../adr/005-product-direction-framework.md).
> Changes to workflow-benchmark thresholds and per-phase gate
> conditions are **Tier 3** per ADR-005 §2.3.
>
> **Skeleton with populated workflow-benchmark section.** Per-phase
> detail is populated in the follow-up Tier 2 slice per ADR-005 §7
> rollout step 2.

---

## 1. Universal DoD (every phase inherits)

A slice closes only if ALL of the following are true:

- [ ] Exit criterion for the phase row in
      [`02-roadmap.md`](./02-roadmap.md) is met.
- [ ] All relevant automated tests green. Test count reported in
      the slice's review pack.
- [ ] Security controls from AGENTS.md §3 applicable to the slice
      are satisfied.
- [ ] For slices touching PHI paths (Phase 4+): PHI-exposure
      review landed in `docs/security/`.
- [ ] Audit emission at the right sites (Rule 06, ADR-003 §2).
- [ ] Commit body carries `Tier:`, `Roadmap-Phase:`, and
      carry-forward per AGENTS.md §4.7.6.
- [ ] Carry-forward items from the prior phase reconciled at
      phase-entry.

## 2. Workflow benchmarks (Phase 4+)

**Binding for every Phase 4+ slice.** A slice that introduces or
materially affects a DoD-tracked workflow MUST ship instrumentation
that measures the workflow end-to-end in the `dev` environment and
report the measured value in the review pack.

| Workflow | Target | Where measured | Phase introduced |
| ---- | ---- | ---- | ---- |
| Create patient (dashboard → saved record) | **≤ 10 seconds** | `apps/web` Playwright benchmark against `dev` with synthetic data | 4A |
| Start encounter (today's schedule → encounter started) | **≤ 3 clicks** | Click-count on a canonical scenario | 4C |
| Complete basic visit note (encounter-started → ready-to-sign) for a simple ambulatory visit | **≤ 90 seconds** | Playwright with scripted keystrokes on a SOAP template | 4D |
| Sign note (ready-to-sign → signed) | **≤ 1 click** | UI interaction count | 4D |
| Refill medication (patient chart → refill queued) | **≤ 15 seconds** | Playwright | 4E |

**Benchmarking against competitors:** measured quarterly against
Elation, Healthie, Canvas, Atlas (and any niche-adjacent competitor
surfaced by `01-competitive-landscape.md`). Results posted to
`docs/evidence/workflow-benchmarks-YYYY-QN.md`. Methodology:
identical scenario, identical operator, wall-clock and click-count
both recorded. Competitor screenshots redacted for attribution but
preserved for evidence.

**Missing a benchmark:** a slice that fails to meet its DoD
threshold does not close the phase. It becomes a carry-forward
item until the benchmark is met. Slippage of more than 20% from
target requires an ADR justifying the gap (e.g., a hard UX
constraint that the roadmap missed) and either amending the
threshold or committing to the fix.

## 3. Per-phase DoD

Each phase row in `02-roadmap.md` has an **Exit** clause. This
section expands each with the phase-specific checklist.

<!-- TODO(content): per-phase checklists populated in follow-up
     Tier 2 slice. Structure:

     ### 3.X Phase NNN
     - [ ] Exit criterion summary
     - [ ] Specific test coverage
     - [ ] Specific audit events present
     - [ ] Specific artifacts (runbooks, ADRs, etc.) landed
     - [ ] Workflow benchmarks met (where applicable)
     - [ ] Carry-forward resolved
-->

## 4. Production-readiness checklist (Phase 6D onwards)

A slice at Phase 6D or later additionally satisfies:

<!-- TODO(content): populated in follow-up slice. Structure:

     - [ ] Runbook rehearsal recorded
     - [ ] DR test performed this quarter
     - [ ] Access review performed this quarter
     - [ ] Claim ledger reconciled this quarter
     - [ ] No open Sev-1/Sev-2 incidents
-->

---

*Last reviewed: 2026-04-21 (skeleton + workflow benchmarks).
Next review: 2026-05-05 (per-phase detail population).*
