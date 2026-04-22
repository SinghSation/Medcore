---
status: Draft
last_reviewed: 2026-04-21
next_review: 2026-05-05
cadence: living-quarterly
owner: Repository owner
---

# Medcore — UX and Clinician Workflow

> Workflow-first principles, design-system boundaries, accessibility
> baseline, and the evidence cadence that proves Medcore is measurably
> faster than competitors on the workflows that determine adoption.
>
> Governed by [ADR-005](../adr/005-product-direction-framework.md).
> Workflow-benchmark **thresholds** live in `03-definition-of-done.md`
> §2 (Tier 3). The **principles**, **design-system rules**, and
> **accessibility baseline** in this document are Tier 2 unless a
> change moves a benchmark threshold.
>
> **Skeleton.** Full content populated in the follow-up Tier 2 slice
> per ADR-005 §7 rollout step 2.

---

## 1. Why UX is a first-class concern

The primary competitive dimension for ambulatory and DPC EHRs is
seconds per common clinician workflow. Clinician burnout and
after-hours documentation ("pajama time") are driven by EHR UX,
not by the underlying clinical complexity. Medcore's win condition
in the DPC niche is to be measurably faster than Elation, Healthie,
Canvas, and Atlas on the workflows in §2 below.

Full competitor context: see
[`01-competitive-landscape.md`](./01-competitive-landscape.md).

Business context: see
[`00-vision.md`](./00-vision.md) §4.1 (workflow speed as
competitive lever).

## 2. Workflow benchmarks

Authoritative table lives in
[`03-definition-of-done.md`](./03-definition-of-done.md) §2.
Summary here for discoverability:

- Create patient: **≤ 10 seconds**
- Start encounter: **≤ 3 clicks**
- Complete basic visit note: **≤ 90 seconds**
- Sign note: **≤ 1 click**
- Refill medication: **≤ 15 seconds**

Each Phase 4+ slice that introduces or materially affects a
benchmarked workflow MUST ship instrumentation and report the
measured value.

## 3. Workflow-first design principles

<!-- TODO(content): populated in follow-up slice. Seeded themes:

     - Speed is a feature. Cognitive load is a defect.
     - Default to keyboard. Every frequent action has a keyboard
       path. Mouse is not required for a full clinician shift.
     - Density modes: "clinician dense" vs "patient regular".
       Clinicians are trained users; density is a performance
       affordance, not a violation of aesthetics.
     - Structured data collection where clinical-decision-support-
       adjacent features MAY benefit (Phase 7+); free-text where
       speed trumps structure (free-text reason for visit is fine
       in 4D; LOINC-coded observations are not).
     - No modal dialogs for routine flows. Modals interrupt; side
       panels and inline expansions do not.
     - Single-page app over page reloads for the clinician surface;
       patient portal may be either.
     - Predictable undo. Every destructive action is recoverable
       for at least 60 seconds.
     - Offline-tolerant draft persistence (Phase 6C).
     - Zero-click defaults: the form that opens has the most likely
       answer already filled in. The clinician confirms or changes,
       not fills.
-->

## 4. Design system boundaries

<!-- TODO(content): populated in follow-up slice. Seeded:

     - Tailwind is the styling substrate. Custom CSS is only
       permitted in `packages/ui/` for composed components.
     - Radix UI primitives are the behavior substrate (Dialog,
       Popover, Dropdown, etc.). Handwriting accessibility for
       a common primitive is prohibited unless Radix cannot solve
       the case.
     - Design tokens live in `packages/ui/tokens/`. Hex colors
       outside the token set are prohibited in product code.
     - Storybook is required for every exported component in
       `packages/ui/`. Stories document the intended usage,
       accessibility notes, and density modes.
     - Icon set: single library, TBD in follow-up content
       (Lucide is a likely pick; decided in 3L).
     - Typography scale and spacing scale enforced via tokens.
-->

## 5. Accessibility baseline

**WCAG 2.1 AA** is the required baseline for every clinician-
facing and patient-facing surface. Specific requirements:

<!-- TODO(content): populated in follow-up slice. Seeded:

     - Color contrast: 4.5:1 for body text, 3:1 for large text.
     - Keyboard navigation: every interactive element reachable
       and operable by keyboard; focus visible.
     - Screen reader semantics: Radix primitives carry
       ARIA out of the box; custom components MUST replicate.
     - Form labels always associated; error messages
       programmatically associated with their inputs.
     - Motion: no animations longer than 300ms on critical-path
       clinician surfaces; respect `prefers-reduced-motion`.
     - Testing: axe-core runs in CI via Vitest (Phase 3L). Every
       PR touching `apps/web` or `packages/ui/` runs the a11y
       suite.
-->

## 6. Usability evidence cadence

<!-- TODO(content): populated in follow-up slice. Seeded:

     - Pre-pilot (Phase 4+): internal usability walkthroughs on
       every DoD-tracked workflow. Video recorded for review.
     - Phase 5D+ (telehealth-lite pilot): first real clinician
       feedback session on note-entry workflow. Structured
       observation, not a survey.
     - Phase 6D (DPC pilot): pilot clinician + practice manager
       interviewed at 30 / 60 / 90 days on actual workflow times,
       pain points, feature gaps.
     - Quarterly post-pilot: usability study on one focus workflow,
       methodology documented in docs/evidence/.
-->

## 7. Competitor benchmarking methodology

<!-- TODO(content): populated in follow-up slice. Seeded:

     - Scenario script: identical across Medcore and competitor.
       Canonical DPC visit — new patient, single encounter,
       3-problem visit, 2 medication refills, 1 new
       problem added.
     - Measurement: wall-clock (video timer) and click-count
       (manual observation).
     - Operator: same person across all runs within a quarter
       (consistency > representativeness at this sample size).
     - Evidence: recorded to docs/evidence/workflow-benchmarks-
       YYYY-QN.md with competitor screenshots redacted.
     - Ethical scope: Medcore uses its own licensed access to
       competitor products. No reverse engineering, no screen
       scraping, no terms-of-service violations.
-->

---

*Last reviewed: 2026-04-21 (skeleton with workflow benchmarks
cross-reference). Next review: 2026-05-05 (full content
population) and quarterly thereafter.*
