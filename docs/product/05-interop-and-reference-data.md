---
status: Draft
last_reviewed: 2026-04-21
next_review: 2026-05-05
cadence: living-per-interop-slice
owner: Repository owner
---

# Medcore — Interoperability and Reference Data

> Authoritative plan for FHIR R4 + US Core + SMART App Launch 2.x +
> USCDI v3 external surface, and for licensing and loading clinical
> reference terminologies.
>
> Governed by [ADR-005](../adr/005-product-direction-framework.md).
> The **reference-data licensing stance** is Tier 3 per ADR-005 §2.3
> (changing CPT scope, adding a newly-licensed terminology, etc.).
> Other content is Tier 2.
>
> **Skeleton.** Full content populated in the follow-up Tier 2 slice
> per ADR-005 §7 rollout step 2.

---

## 1. Standards baseline

- **FHIR R4** — the permanent stable HL7 publication. Medcore's
  external data model.
- **US Core Implementation Guide** (most recent stable version at
  slice time) — US-specific constraints on FHIR R4 resources.
- **SMART App Launch 2.x** — OAuth-based authorization for
  third-party app integration with Medcore's FHIR surface.
- **USCDI v3** — ONC's required data-class baseline effective
  2026-01-01. Medcore tracks conformance without claiming ONC
  certification (see `04-compliance-and-legal.md` §2.8).
- **Bulk Data Access IG** — for `$export` / `$import`.

## 2. Internal → FHIR mapping strategy

**Architectural stance:** Medcore uses a clean internal domain
model. The FHIR surface is a projection, not the source of truth.
See `00-vision.md` §4.2 and the architecture charter §2.4
(Interoperability by Default).

<!-- TODO(content): per-resource mapping tables:

     ### 2.X Patient
     Internal entity → FHIR Patient. Per-field mapping. Required
     US Core elements. Must-support elements. Mapping gaps.

     Repeat for: Appointment, Slot, Encounter, Condition,
     MedicationStatement, MedicationRequest, AllergyIntolerance,
     Observation (vitals + labs), DiagnosticReport, ServiceRequest,
     DocumentReference.
-->

## 3. SMART App Launch 2.x

<!-- TODO(content): supported launch flows (EHR-launch, standalone
     launch), scope set, consent UI, app registration model,
     refresh-token handling. -->

## 4. Bulk Data Access

<!-- TODO(content): $export flavors supported (patient, group,
     system), async job model, signed URL TTLs, data retention on
     export, $import decision (likely deferred beyond 5B). -->

## 5. USCDI v3 alignment

<!-- TODO(content): USCDI v3 data-class coverage matrix. Columns:
     data class, mapped internal entity, FHIR resource, Medcore
     support status (in Phase 5A / in Phase 5B / deferred). -->

## 6. Conformance testing

<!-- TODO(content): Inferno touchstone plan. Which test kits run
     in CI (Phase 3I+), which run manually pre-release. Scope
     commitment: Medcore tests but does not claim certification
     per 00-vision.md §5. -->

## 7. Reference data

Full licensing table is canonical in this section. Compliance-
impact summary appears in `04-compliance-and-legal.md` §6.

| Terminology | US license | Cost | Medcore use | Phase |
| ---- | ---- | ---- | ---- | ---- |
| ICD-10-CM | Free (CMS publishes) | $0 | Problem list Condition.code (when coded) | 3M |
| SNOMED CT US Edition | Free via UMLS (registration required) | $0 | Problem list, encounter reasons, findings | 3M |
| LOINC | Free | $0 | Vital-signs Observation.code, lab-results Observation.code | 3M |
| RxNorm | Free | $0 | Medication.code normalization | 3M |
| NDC Directory | Free (FDA) | $0 | Dispensed medication identification | 3M |
| **CPT** | **AMA-licensed, commercial** | **Commercial; per-user or per-implementation pricing** | **Billing codes (claims scope)** | **Phase 10 — licensing ADR required** |
| HCPCS Level II | Free (CMS) | $0 | Billing codes (claims scope) | Phase 10 |
| ICD-10-PCS | Free | $0 | Inpatient procedure coding | Phase 12 (hospital niche) |

**Phase 3M scope (current):** load the free terminologies. The
CPT licensing ADR is authored in 3M; licensing may be deferred to
Phase 10 but the decision is written down.

### 7.1 Loading strategy

<!-- TODO(content): per-terminology loading strategy, version
     metadata, update cadence (UMLS ships SNOMED/RxNorm quarterly;
     LOINC semiannually; ICD-10-CM annually October cycle), RLS
     scope (reference schema is shared, not tenant-scoped), search
     expectations (none in 3M; later phases may add). -->

### 7.2 FHIR terminology binding

<!-- TODO(content): ValueSet / CodeSystem posture; whether Medcore
     publishes its own FHIR terminology resources in Phase 5A;
     external binding to VSAC. -->

---

*Last reviewed: 2026-04-21 (skeleton with reference-data table
populated). Next review: 2026-05-05 (full content population) and
per-interop-slice thereafter.*
