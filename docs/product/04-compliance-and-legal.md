---
status: Active
last_reviewed: 2026-04-21
next_review: 2026-07-21
cadence: living-quarterly
owner: Repository owner
---

# Medcore — Compliance & Legal Readiness

> Authoritative statement of Medcore's current compliance posture,
> regulatory scope, the maturity model that governs how posture
> improves, and the standing controls that prevent overclaiming.
> Governed by [ADR-005](../adr/005-product-direction-framework.md).
>
> **Maturity phases**, the **claim ledger reconciliation**, and the
> **SaMD boundary** are Tier 3 changes per ADR-005 §2.3. Other content
> is Tier 2.
>
> **Non-legal-advice disclaimer:** this document is an engineering and
> product compliance framework, not legal advice. Specific regulatory
> interpretations — particularly state-by-state privacy law, BAA
> negotiation, and breach-notification obligations — MUST be reviewed
> with qualified counsel before any pilot onboarding (Phase 6D) or
> compliance attestation.

---

## 1. Compliance Maturity Phases

Medcore's compliance posture matures in four declared phases. Each
phase has explicit entry and exit criteria. The current phase is the
single source of truth for what Medcore may claim in public copy.

### 1.1 Phase A — Architecture-ready *(current)*

**Definition:** The architecture and code correctly embody the
controls a compliance program will require, but operational
controls (policies, training, incident response, risk assessment,
access reviews, vendor BAAs, customer BAAs) are not yet in place.

**Evidence Medcore can cite at Phase A:**
- HIPAA-aligned architecture: row-level multi-tenancy enforced at
  the database (Phase 3E), append-only audit with cryptographic
  chaining (Phase 3D), deny-by-default authorization, OIDC-only
  authentication (Phase 3A.3).
- No PHI in the repository. `.gitignore` patterns block common
  PHI filename forms. Fixtures are synthetic (Synthea-based from
  Phase 3M).
- Governed AI development model (ADR-004) with audit trail
  (every commit body carries tier classification and rationale).
- ADR-governed change control; every decision immutable.

**Entry:** Phase 3E landed (which it has).

**Exit (→ B):** every Phase B criterion below satisfied.

### 1.2 Phase B — Operational-ready

**Definition:** The policies, runbooks, and routine-control evidence
needed to operate Medcore in production are in place and rehearsed.
Medcore can honestly stand up a compliance conversation with a
buyer's security team.

**Criteria to enter Phase B:**
- **Risk assessment** performed and documented, per HIPAA
  §164.308(a)(1)(ii)(A). Annual cadence established.
- **Written policies** exist in `docs/compliance/` for: information
  security, access management, incident response, breach
  notification, sanctions, workforce training, contingency
  (backup/DR), evaluation, BAA requirements, minimum-necessary,
  data retention/disposal, password/credential, acceptable use,
  change management. Solo-founder operation does not exempt the
  need for written policies; it allows them to be concise.
- **Incident response plan** documented and rehearsed once
  (tabletop exercise, documented in `docs/evidence/`).
- **Breach notification procedure** documented with the 60-day
  HHS notification clock, 60-day affected-individual
  notification, and the >500-individual press/HHS-Secretary
  notification path (HIPAA Breach Notification Rule,
  §§164.400–414).
- **Access review cadence** established (quarterly minimum). Even
  for solo operation, the review is documented.
- **Backup + DR tested**: automated RDS backups, restore tested
  at least once end-to-end, documented.
- **Change management** formalized via existing ADR discipline +
  CI (Phase 3I provides the CI control).
- **Customer BAA template** drafted and legal-reviewed.
- **Vendor BAA inventory**: AWS BAA executed (automatic on AWS
  account with HIPAA-eligible services), Stripe BAA executed
  (Phase 6A adjacency), any other sub-processors covered before
  Phase 6A.
- **Workforce training** (even solo-operator): documented
  self-training on HIPAA Security Rule, annual refresher.
- **Evidence folder structure** populated in `docs/evidence/`
  with artifacts for each policy and rehearsal.
- **Entity formation + insurance**: LLC or C-corp formed; E&O
  insurance in place; cyber insurance in place. These are
  legal/business prerequisites, not compliance artifacts, but
  tracked here for operational readiness.

**Exit:** Phase 6D operating under Phase B controls without
incident for at least one complete billing cycle.

### 1.3 Phase C — Market-ready

**Definition:** External validation of posture. External pentest
completed with findings remediated. SOC 2 Type I readiness
assessment or formal engagement underway. Customer-facing security
package (trust center) published.

**Criteria to enter Phase C:**
- **External pentest** (Phase 8) executed with findings
  remediated.
- **SOC 2 Type I** readiness assessment complete OR formal Type I
  audit engagement started with a CPA firm.
- **Trust center** published: a public page (or docs site section)
  describing Medcore's security controls at Phase C maturity,
  linked to the current claim ledger from `00-vision.md` §5.
- **Customer security package**: a standard document a
  prospective buyer's security team can review (summary of
  controls, architecture overview, compliance matrix, subprocessor
  list, incident history).
- **Formal privacy review** of public-facing surfaces: patient
  portal, API surface, any marketing site. Privacy notice lives
  under `docs/legal/`.

**Exit:** SOC 2 Type I attestation received OR deferred with a
documented ADR explaining why (e.g., the target customer base
doesn't require it). Move to Phase D requires Type II engagement.

### 1.4 Phase D — Enterprise-sales-ready

**Definition:** SOC 2 Type II attestation. HITRUST CSF considered
(and either pursued or explicitly deferred). Certified interop
track (ONC) considered (and either pursued or explicitly deferred
per `00-vision.md` §3.2).

**Criteria to enter Phase D:**
- **SOC 2 Type II** attestation received. Requires a 6–12 month
  residency period after Type I.
- **HITRUST CSF** decision (pursue / defer / reject) documented in
  a dedicated ADR.
- **ONC certification track** decision documented in a dedicated
  ADR per Phase 11.
- **Formal clinical safety governance** operational: safety case
  (§5), hazard log reviewed per slice, named clinical safety
  officer (internal or fractional).

---

## 2. Regulatory Scope

### 2.1 HIPAA (Privacy, Security, Breach Notification)

Medcore is a **business associate** under HIPAA. Customers (the
provider practices) are **covered entities**. The BAA governs
Medcore's PHI handling on the customer's behalf.

**Scope of coverage:**
- **Privacy Rule** (45 CFR 164.500–534): applies to Medcore via
  customer BAAs. Minimum-necessary, patient rights
  (access/amendment/accounting of disclosures) are implemented at
  the product level.
- **Security Rule** (45 CFR 164.302–318): applies directly to
  Medcore as a business associate. The administrative, physical,
  and technical safeguards are implementation-mapped in
  `docs/compliance/hipaa-security-rule-mapping.md` (populated in
  Phase B).
- **Breach Notification Rule** (45 CFR 164.400–414): 60-day
  reporting to customer CEs; 60-day patient notifications where
  applicable; >500 individuals triggers HHS and media
  notifications.

**Not in scope until Phase C:**
- Formal HHS OCR audit readiness evidence set.
- Ongoing HIPAA risk assessment cycle (initial assessment lands
  in Phase B; cycle matures in Phase C).

### 2.2 HITECH

HITECH (2009) strengthens HIPAA for electronic PHI, mandates
breach notification, and establishes business-associate direct
liability. HITECH obligations are folded into HIPAA operations
above. No separate track.

### 2.3 State Privacy Laws

State privacy laws MAY be stricter than HIPAA on specific
dimensions (broader than PHI, shorter breach windows, additional
data rights). Medcore scopes the following:

- **California (CCPA / CPRA):** applies to non-HIPAA data paths
  (patient portal marketing cookies, analytics, any non-PHI
  PII). HIPAA-covered PHI is carved out under CPRA §1798.145(c).
- **Washington (My Health My Data Act, effective 2024):** broader
  than HIPAA; covers "consumer health data" which includes some
  data outside the HIPAA envelope. Relevant to patient-portal
  activity. Requires consent for collection of health data and a
  distinct privacy policy section.
- **Texas, Nevada, Connecticut, and others** have enacted health-
  data or broader privacy laws with patient-data implications. A
  state-by-state matrix lands in `docs/compliance/state-privacy-
  matrix.md` in Phase B.

**Phase A posture:** scoped but not operationalized. State-by-state
compliance lands with Phase 6B (patient portal) and Phase 6D
(pilot onboarding) in states where the pilot operates.

### 2.4 FTC §5 (unfair or deceptive acts)

The FTC has actively enforced against healthcare-adjacent
companies for overclaiming privacy or security posture. Section 5
applies regardless of HIPAA status. The **claim ledger** in
`00-vision.md` §5 is the standing control against FTC §5 exposure.

**Phase A posture:** active. No overclaims in repo text, README,
or commit bodies. Claim ledger reconciled quarterly.

### 2.5 FDA Software-as-Medical-Device (SaMD) boundary

Most EHR functions are **not** medical devices. Specific features
can cross into SaMD territory — in particular:
- Clinical Decision Support beyond reference lookup (drug-drug
  interaction alerts have a carve-out; auto-diagnostic
  suggestions do not).
- Dose calculators that make the clinician's decision for them.
- Image analysis.
- Any software that "is intended to treat, diagnose, cure,
  mitigate, or prevent a disease or other conditions" and is not
  covered by the 21st Century Cures Act exemption for clinical
  decision support (which applies only when the software
  provides information, permits the user to independently
  review, and otherwise meets the four-factor test).

**Medcore's standing boundary:** at Phases 4–6, Medcore is a
documentation and workflow tool. It does not provide CDS beyond
reference lookup (medication list, allergy list, problem list —
data presentation, not inference). It does not calculate doses.
It does not analyze images. No FDA clearance is sought.

**Crossing this boundary requires:**
1. A superseding ADR naming the specific feature.
2. An FDA regulatory analysis (internal or external counsel).
3. If the feature is SaMD: a clinical safety case per §5 below,
   a 510(k) or equivalent pathway, and QMS conformance (21 CFR
   820 or ISO 13485 equivalent).

**Reviewed quarterly.** Features proposed during Phases 4–6 are
screened against the boundary; any suspect feature halts for ADR.

### 2.6 21 CFR Part 11 (electronic records and signatures)

Applies to FDA-regulated electronic records. Relevant if Medcore
later supports clinical trials, regulated research, or
pharmaceutical data. **Not in scope** for Phases 4–10.
Signed clinical notes follow HIPAA's integrity controls, not Part
11. A future scope expansion (research / trials support) would
bring Part 11 into play under an ADR.

### 2.7 GDPR

Applies if Medcore serves EU data subjects or processes EU health
data. **Not in scope** at Phase A through Phase 10 — Medcore is
US-focused. A non-US customer request is an ADR event.

### 2.8 ONC Health IT Certification

Voluntary unless the customer requires CEHRT for incentive
programs (MIPS, Promoting Interoperability). DPC practices,
cash-pay clinics, and most telehealth startups do not require
CEHRT. Certification is deferred to Phase 11 per `00-vision.md`
§3.2.

**Key ONC-adjacent obligations Medcore tracks regardless of
certification:**
- **USCDI v3** (US Core Data for Interoperability, version 3)
  became the required baseline for ONC-certified EHRs effective
  2026-01-01 per the HTI-1 final rule. Medcore's Phase 5 interop
  plan aligns with USCDI v3 conformance but does not claim
  certification.
- **21st Century Cures Act information-blocking provisions**
  (45 CFR 170, 171): information blocking rules apply to "actors"
  defined as health care providers, health IT developers of
  certified health IT, HIEs, and HINs. **Medcore's status:**
  not-yet-certified health IT, so Medcore itself is likely not an
  actor under the rule. Medcore's customers (CE providers) are
  actors. Medcore's FHIR-native, no-data-hostage posture supports
  customers' information-blocking obligations. Formal
  interpretation pending counsel review at Phase B.

### 2.9 Payment Card Industry Data Security Standard (PCI DSS)

Applies to entities that store, process, or transmit cardholder
data. **Medcore's standing posture: zero cardholder-data
storage.** All payment flows route through Stripe at Phase 6A.
Stripe is PCI Level 1 and bears PCI scope.

Medcore claims **zero CHD storage**, not **PCI compliance**.
Collateral copy and marketing text MUST use the former framing.

### 2.10 EPCS (Electronic Prescribing of Controlled Substances)

Per 21 CFR 1311. Requires DEA-specified identity proofing,
logical access controls (two-factor with ≥1 hard token),
digital signatures, and third-party certification.

**Not in scope until Phase 9+.** The telehealth niche makes EPCS
a near-future requirement but it is a discrete project under its
own ADR, not a feature added casually.

---

## 3. Business Associate Agreements

### 3.1 Customer BAA

Medcore's customers (provider practices) are covered entities.
Medcore is a business associate. A BAA is required before any
real PHI flows.

**Posture at Phase 6D:** BAA template in `docs/legal/` reviewed
by counsel. Template follows HHS model BAA provisions (45 CFR
164.504(e)) with Medcore-specific sections for:
- subprocessor list (AWS, Stripe, any others);
- audit log access procedure;
- breach notification timing (24 hours to CE after discovery,
  giving the CE the 60-day window);
- data return / destruction on contract termination.

**Phase A posture:** draft BAA in `docs/legal/template-baa.md`
(Phase 6D). No customer has signed; no real PHI flowing.

### 3.2 Vendor BAAs (Medcore as CE equivalent for purposes of
tracking)

Medcore MUST have a BAA with every vendor that touches PHI.

| Vendor | Service | BAA status | Phase requiring |
| ---- | ---- | ---- | ---- |
| AWS | Infrastructure (RDS, S3, ECS, KMS, etc.) | Auto on HIPAA-eligible services with BAA enabled on account | 3I |
| Stripe | Payment processing (no PHI storage by design, but data envelope is covered) | Required | 6A |
| Video vendor (Twilio/Daily/Zoom) | Telehealth video | Required | 5D or 9 |
| Email vendor (SES/SendGrid/Resend) | Transactional email | Required if any email contains patient identifiers | 4A+ |
| Video conferencing vendor | see Telehealth | — | — |
| Observability vendor (Datadog/etc.) | Logs, metrics, traces | Required — Medcore logs MUST be PHI-free by design, but the BAA is defense-in-depth | Post-3F if a vendor is used; otherwise CloudWatch only |
| Ambient documentation vendor (Phase 6C+ / 9+) | Audio transcription | Required | 6C+ |

**Standing control:** no vendor integration (new MCP server, new
API client, new infrastructure) ships without a BAA-status entry
in this table. Missing entry halts the slice.

---

## 4. Insurance

Required before Phase 6D:

- **Errors & Omissions / Professional Liability:** covers
  professional service failures. Minimum $1M recommended.
- **Cyber Liability:** covers breach response costs, notification
  costs, regulatory fines where insurable. Minimum $1M
  recommended; higher depending on PHI scale.
- **General Liability:** standard business coverage.
- **Tech E&O:** often bundled with Cyber; covers software errors.

**Phase A posture:** not in place. Tracked as a Phase B
prerequisite.

---

## 5. Clinical Safety Governance

### 5.1 Current posture (Phases 4–6)

Medcore operates as a **documentation and workflow tool**. It
does not:
- Make clinical decisions.
- Recommend diagnoses.
- Calculate doses.
- Analyze images.
- Auto-code encounters.
- Flag drug-drug interactions with severity-based intrusion
  (the reference display of a medication and an allergy is
  data presentation, not inference).

No formal clinical safety case, no hazard log, no clinical
safety officer is required at this posture.

**What Medcore DOES require at Phase A–6:**
- No CDS claims in product copy or marketing.
- Feature proposals screened against the SaMD boundary (§2.5).
- PHI-exposure review per Phase 4+ slice.
- Audit logs support retrospective safety review.

### 5.2 Deferred-until-Phase-7 posture

At Phase 7, Medcore introduces formal clinical safety governance:

- **Clinical safety case document** (`docs/compliance/clinical-
  safety-case.md`): a living document enumerating identified
  clinical hazards, their likelihood × severity assessments, and
  their mitigations. Modeled on the UK DCB0129/DCB0160 framework
  adapted for US context. Even if no US regulatory requirement
  mandates this structure, it is a defensible pattern.
- **Hazard log** (`docs/compliance/hazard-log.md`): every
  clinically-relevant behavior change reviews the hazard log
  and adds entries as needed.
- **Named clinical safety officer**: initially the repository
  owner (solo founder). Documented. At a future headcount
  threshold, a clinician reviewer is named (fractional or
  employed).

Crossing from Phase 4–6 posture to Phase 7 posture is triggered
by the first feature that requires CDS adjacency, OR by Phase 7's
entry criteria being met organically.

---

## 6. Reference-Data Licensing Obligations

Clinical reference terminologies have licensing implications that
affect Medcore's cost structure and legal posture. Full table is
in `05-interop-and-reference-data.md` §Reference Data. Summary
here for compliance visibility:

| Terminology | US license | Cost | Phase |
| ---- | ---- | ---- | ---- |
| ICD-10-CM | Free (WHO; CMS publishes US version) | $0 | 3M |
| LOINC | Free | $0 | 3M |
| SNOMED CT US Edition | Free via UMLS (registration required) | $0 | 3M |
| RxNorm | Free | $0 | 3M |
| NDC Directory | Free (FDA) | $0 | 3M |
| **CPT** | **AMA-licensed, commercial** | **$$$ (per-user and/or per-implementation)** | **Phase 10** |
| HCPCS Level II | Free (CMS) | $0 | Phase 10 |

**Standing obligation:** the CPT licensing ADR (Phase 3M) decides
whether Medcore licenses CPT for Phase 10 claims work. Until that
ADR accepts, CPT codes MUST NOT appear in the repository beyond
example code fragments in docs.

---

## 7. Claim Ledger Reconciliation

The public claim ledger lives in `00-vision.md` §5. This section
is the operational reconciliation: when does a claim leave the
"not claimed" list?

| Claim | Reconciliation condition |
| ---- | ---- |
| HIPAA compliant | Compliance Maturity Phase C entered. OCR-style audit-readiness artifact set complete. |
| SOC 2 Type I attested | Formal Type I report received from CPA firm. |
| SOC 2 Type II attested | Formal Type II report received from CPA firm. |
| HITRUST CSF certified | Certification received from HITRUST authorized assessor. |
| ONC Health IT certified | ONC-ATL certification received; CHPL listing active. |
| HL7 FHIR R4 conformant (Inferno) | Inferno touchstone run passes all applicable scenarios; evidence in `docs/evidence/`. Note: this is a self-affirmed claim, not a certification. |
| 21st Century Cures information-blocking compliant | Counsel review confirms Medcore is not an actor OR Medcore is an actor and complies with 45 CFR 171; evidence in `docs/evidence/`. |
| EPCS certified | Third-party EPCS certification per 21 CFR 1311 received. |
| FDA-cleared CDS device | 510(k) clearance received for the specific feature. |
| Commercial production ready | Phase 6D exit criteria met. |

**Reconciliation rule:** moving a claim from "not claimed" to
"claimed" is a Tier 3 change to `00-vision.md` §5 AND requires an
evidence artifact in `docs/evidence/` referenced from the updated
claim. Moving backward (claim withdrawn) is also Tier 3 and is
treated as an incident.

---

## 8. Change-Tracking for This Document

This document is living (cadence: quarterly). Each quarterly
review MUST:

1. Reconcile the claim ledger (§7) against actual operational
   posture. Any drift is either fixed or documented with a
   remediation plan.
2. Refresh the regulatory-scope entries in §2 against any new
   state privacy legislation, ONC rule updates, or FDA guidance.
3. Refresh the vendor BAA inventory (§3.2).
4. Confirm the SaMD boundary (§2.5) has not been accidentally
   crossed in any recent slice.
5. Update `last_reviewed` and `next_review` frontmatter.

---

*Last reviewed: 2026-04-21. Next review: 2026-07-21 (quarterly).*
