---
status: Active
last_reviewed: 2026-04-21
next_review: 2027-04-21
cadence: stable-yearly
owner: Repository owner
---

# Medcore — Vision, Positioning, and Locked Decisions

> This document is the authoritative forward-looking statement of what
> Medcore is, who it serves, how it competes, and what it will and will
> not claim. Governed by
> [ADR-005](../adr/005-product-direction-framework.md). Changes to any
> section are Tier 2 by default; changes to §3 (locked decisions) and
> §5 (claim ledger) escalate to Tier 3 per ADR-005 §2.3 and
> [Rule 08](../../.cursor/rules/08-product-docs-maintenance.mdc) §3.

---

## 1. Mission

Medcore is an enterprise-grade, AI-governed Electronic Health Record
platform built to let a clinician spend less time documenting and more
time in the room with a patient, while giving the people who run the
practice a clean, interoperable, auditable, and affordable system of
record.

The platform is built by a solo founder with AI assistance. The
repository itself enforces the invariants that normally require a large
engineering organization: fail-closed security, row-level multi-tenancy
enforced at the database, append-only cryptographically chainable
audit, contract-first APIs, tiered governance on every commit, and a
living product-direction surface reviewed on a schedule.

Medcore is designed to compete with the established EHR market makers
(Epic, Oracle Health, athenahealth, eClinicalWorks, NextGen) in a
specific subset of the market, and with modern ambulatory-focused
competitors (Elation, Healthie, Canvas, Atlas) in the DPC and
telehealth niches where the incumbents' weight works against them.

## 2. Target Niches and Sequencing

Medcore will serve four healthcare-business niches in a declared order.
Each niche has distinct regulatory, integration, and workflow
requirements; building all four simultaneously is infeasible for a
solo-founder program. The declared order below is binding until
superseded by an ADR.

| Order | Niche | Why this position |
| ---- | ---- | ---- |
| **1 — primary** | **Direct Primary Care (DPC)** | Smallest operational surface. Cash-pay membership model; no insurance claims complexity. Clinicians are often solo or small-group and decide their own tech. Low ONC pressure. Highest fit between "what Medcore can build" and "what a practice needs" in year one. |
| **2 — expansion** | **Telehealth** | Per-visit billing is simpler than subscriptions. Workflow is linear (schedule → join → document → sign). Lower adoption friction; faster first paying pilot is possible via a telehealth-lite accelerator (see §4). Longer-term product-fit weaker than DPC — use as accelerator and second-niche market, not the strategic center. |
| **3 — later** | **Ambulatory clinics (non-DPC)** | Insurance claims, eligibility, prior auth, clearinghouse integration, payer-specific rules. Significantly more surface area. Entered after the platform's integration muscle exists (Phase 10). |
| **4 — long-term** | **Hospitals** | HL7v2 ADT feeds, ORM/ORU interfaces, CPOE workflows, inpatient order sets, regulated device integration, bed-management. Enterprise IT review cycles measured in quarters. Not a realistic early target. |

**Niche re-ordering requires an ADR** that supersedes ADR-005 with the
revised sequencing. Adding a niche (e.g., skilled nursing, behavioral
health, ASC) is similarly an ADR event.

## 3. Locked Strategic Decisions (ADR-005 §2.2)

These decisions bind every subsequent slice. They move only via a
superseding ADR. Changes to this section escalate to Tier 3 per
ADR-005 §2.3.

### 3.1 Niche order
**DPC → Telehealth → Clinics → Hospitals.** Primary niche commitment
is DPC. A telehealth-lite path (Phase 5D) is permitted as an
accelerator to a first paying pilot without reclassifying telehealth
as the primary niche.

### 3.2 ONC certification
**Deferred.** Medcore does not target ONC-certified status prior to
Phase 9 of the roadmap. The addressable market for Phases 4–8 is
explicitly the set of buyers who do not require ONC-certified EHR
(DPC, cash-pay clinics, many telehealth startups). ONC certification
is revisited when product-market fit exists and certification
materially unlocks revenue Medcore cannot access otherwise.

### 3.3 Time horizon
**First pilot: 9–12 months from 2026-04-21.** **Production-grade
platform: 18–24 months.** Slippage triggers a roadmap review, not a
missed ship date. The target is a real clinician onboarding a real
practice, not a demo or a beta enrollment.

### 3.4 Claim ledger discipline
Medcore publishes a standing public list of claims it does **not** yet
make (§5 of this document). Entries leave that list only when (a)
operational evidence exists, and (b) a superseding statement lands in
`04-compliance-and-legal.md`. Agents MUST refuse to land sales
collateral, README copy, or marketing text that contradicts the
ledger. The claim ledger is a standing compliance control against
FTC §5 (unfair or deceptive acts) exposure.

## 4. How Medcore Competes

Medcore's position is not to match every feature of the incumbents.
The position is to win on a narrow set of dimensions that matter
disproportionately in its target niche and that the incumbents
cannot easily match:

### 4.1 Workflow speed
The primary competitive dimension for ambulatory and DPC EHRs is
seconds per common clinician workflow. Every Phase 4+ slice ships
with measurable workflow benchmarks, enforced via
[Definition of Done](./03-definition-of-done.md) §workflow-benchmarks.
Targets: create patient ≤ 10s, start encounter ≤ 3 clicks, complete
basic visit note ≤ 90s, sign note ≤ 1 click, refill medication
≤ 15s. These are measured against Elation, Healthie, Canvas, and
Atlas every quarter per
[01-competitive-landscape.md](./01-competitive-landscape.md).

### 4.2 Interoperability out of the box
FHIR R4 + US Core read API from the first clinical module. SMART
App Launch 2.x from Phase 5A. Bulk Data export from 5B. USCDI v3
alignment tracked from 5C. Medcore will never hold a customer's
data hostage; the FHIR surface is a first-class feature, not a
compliance afterthought. See
[05-interop-and-reference-data.md](./05-interop-and-reference-data.md).

### 4.3 Transparent pricing and contracts
Medcore will publish pricing. BAAs will be signed with any customer
by default. Terms will not require long lock-in. This is a
competitive lever against incumbents whose pricing opacity and
long-lock contracts are a material buyer friction.

### 4.4 Developer-friendly extension model
Every Medcore API surface is OpenAPI-first (internal) and FHIR-first
(external). A customer's own developer can build integrations
without a vendor engagement. Phase 5A+ publishes the SMART launch
framework so third-party apps integrate via the SMART spec, not a
bespoke contract. This is table stakes against modern competitors
and a moat against incumbents whose integration models are
bespoke-per-customer.

### 4.5 Security and audit posture honest, not aspirational
The claim ledger (§5) means Medcore will never overclaim posture.
Buyers with security-sensitive requirements (DPC practices
handling controlled substances; telehealth companies operating
across state lines) can rely on posture statements because the
statements are evidence-backed. See
[04-compliance-and-legal.md](./04-compliance-and-legal.md).

## 5. Claim Ledger

**Medcore does NOT currently claim any of the following:**

- **HIPAA compliant** — Medcore is built with HIPAA-aligned
  architecture. Formal compliance requires operational controls
  (policies, incident response, workforce training, risk
  assessment, BAAs with all sub-processors, vendor management,
  access reviews) that are phased in per the compliance maturity
  model in `04-compliance-and-legal.md`. As of this ADR, Medcore
  is in **Maturity Phase A** (architecture-ready); Phases B, C, D
  are staged. Claiming "HIPAA compliant" before Phase C is
  complete is prohibited.
- **SOC 2 Type I attested** — no third-party attestation has been
  performed. Reachable in compliance Maturity Phase C with
  external assessor engagement.
- **SOC 2 Type II attested** — requires sustained operation under
  Type I controls for a defined audit period (typically 6–12
  months after Type I). Reachable in compliance Maturity Phase D.
- **HITRUST CSF certified** — not scoped. Consideration deferred
  to post-pilot strategic review.
- **ONC Health IT certified** (including any (g)(10) Standardized
  API criterion) — deferred to Phase 9+ per §3.2. ONC
  certification is a scoped, phased program; claiming it absent
  program participation is prohibited.
- **HL7 FHIR R4 conformant** (per Inferno or equivalent test
  suite) — the implementation targets R4 + US Core conformance;
  formal conformance evidence is produced in Phase 5C.
- **21st Century Cures Act information-blocking compliant** — the
  regulation applies to actors (providers, health IT developers
  of certified health IT, HIEs). Medcore's position under the
  rule depends on its customers' status and Medcore's own
  certification status. Scoped in
  `04-compliance-and-legal.md`; not claimed as of ADR-005.
- **EPCS (Electronic Prescribing of Controlled Substances)
  certified** — requires DEA-specified identity proofing, logical
  access controls, digital signatures, and third-party
  certification per 21 CFR 1311. Scoped to telehealth niche Phase
  9+. Not available until then.
- **FDA-cleared CDS device** — Medcore will not build Clinical
  Decision Support functions that meet the FDA Software-as-
  Medical-Device definition prior to Phase 7+ without a
  superseding ADR and a clinical safety case. Until then,
  Medcore's posture is "documentation + workflow tool, not
  regulated medical device."
- **PCI DSS compliant** — Medcore does not handle cardholder data
  directly. All payment flows in Phase 6A route through Stripe
  (or equivalent PCI-Level-1 processor), which bears PCI scope.
  Medcore claims zero CHD storage, not PCI compliance.
- **Commercial production ready** — Medcore is pre-pilot as of
  ADR-005. Production readiness is reached at Phase 6D (first
  pilot signs BAA and onboards).

**Medcore DOES currently claim the following (evidence-backed):**

- **HIPAA-aligned architecture** — row-level multi-tenancy
  enforced at the database via RLS (Phase 3E), append-only audit
  with cryptographic chaining (Phase 3D), OIDC-only
  authentication (Phase 3A.3), deny-by-default authorization
  posture. Evidence: ADRs 001–004, Phase 3A–3E test packs.
- **No PHI in the repository** — confirmed by
  `.gitignore` patterns, fixtures are synthetic (Synthea-based
  from Phase 3M), PHI-exposure reviews per slice.
- **Governed AI development model** — documented in `AGENTS.md`
  and ADR-004; four phases delivered under the tier model with
  zero governance incidents.

Any claim change (either direction) is a Tier 3 change to this
document.

## 6. Non-Goals

This section is explicit about what Medcore is **not** doing, to
prevent scope drift. Each non-goal is reviewed on the same cadence
as this document.

### 6.1 Not building in the first pilot
- Inpatient workflows (hospital niche is Phase 12).
- Full revenue-cycle management (claim generation, clearinghouse,
  denial management — clinic niche, Phase 10).
- Imaging (PACS integration, DICOM, radiology reporting).
- Lab Information System integration (Phase 4F includes stubs,
  not real LIS).
- Advanced clinical decision support (Phase 7+ at earliest, with
  SaMD review).
- Device integration (vitals monitors, Bluetooth BP cuffs,
  continuous glucose monitors).
- E-prescribing network integration (Surescripts NewRx, RxChange,
  eRefill). Telehealth niche Phase 9.
- Controlled-substance e-prescribing (EPCS per 21 CFR 1311).
  Telehealth niche Phase 9+.
- Claims clearinghouse (837/835) or eligibility (270/271). Clinic
  niche Phase 10.
- Prior authorization (NCPDP SCRIPT ePA, Da Vinci PAS/CRD/DTR).
  Clinic niche Phase 10+.
- HL7v2 ADT/ORM/ORU feeds. Hospital niche Phase 12.

### 6.2 Not doing at any phase without explicit superseding ADR
- Storing real patient PHI in a development environment.
- Claiming certifications that have not been formally achieved.
- Building into the main repository any feature that requires
  FDA 510(k) clearance as an SaMD.
- Disabling audit logging, even temporarily.
- Bypassing OIDC for any user-facing authentication path.
- Operating the application as a database superuser in any
  environment (ADR-001 §2; Phase 3E).

### 6.3 Explicitly chosen trade-offs
- Not pursuing multi-cloud or cloud-agnostic architecture. Medcore
  targets AWS. A migration would be an ADR event; the base case
  is AWS lock-in as an acceptable cost for operational simplicity.
- Not pursuing Kubernetes for initial deployment (Phase 3I).
  ECS Fargate is the target compute substrate until scale
  demands otherwise.
- Not adopting microservices. Modular monolith per the charter
  and ADR-004.

## 7. Success Criteria (18–24 month horizon)

Medcore succeeds at Phase 6D (first pilot onboarded) when:

- At least one real DPC practice has signed a BAA and is
  operating Medcore as its sole EHR for at least one complete
  billing cycle.
- Workflow benchmarks in [`03-definition-of-done.md`](./03-definition-of-done.md)
  are met or exceeded on that customer's actual clinician usage.
- Zero PHI incidents have occurred since production onboarding.
- Audit evidence (chain verification, access logs, incident log
  empty) is complete and reviewable by the customer's compliance
  officer on request.
- Medcore's own compliance Maturity Phase is at least B
  (operational-ready) with a documented path to C (market-ready).

Medcore succeeds at Phase 9 (telehealth niche shipped) when:

- At least three telehealth practices (or telehealth-only
  providers) are operating Medcore.
- Video and Surescripts integration are operational.
- Compliance Maturity Phase C (market-ready) is achieved.

## 8. Governance

This document is governed by
[ADR-005](../adr/005-product-direction-framework.md). Maintenance
discipline is in
[`.cursor/rules/08-product-docs-maintenance.mdc`](../../.cursor/rules/08-product-docs-maintenance.mdc).
Changes to §3 (locked decisions) or §5 (claim ledger) escalate to
Tier 3. Other sections are Tier 2 by default.

Authority order (highest first): applicable law/regulation →
`AGENTS.md` → architecture charter → accepted ADRs →
`.cursor/rules/` → this document. Conflicts resolve upward.

---

*Last reviewed: 2026-04-21. Next review: 2027-04-21 (yearly cadence),
or sooner if any of §§2, 3, 5, or 6 becomes materially inaccurate.*
