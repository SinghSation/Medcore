---
status: Active
last_reviewed: 2026-04-23
next_review: 2026-07-25
cadence: living-per-slice
owner: Repository owner
---

# Medcore — Roadmap

> This document is the authoritative forward-looking phase plan. Every
> code-bearing commit MUST reference a phase from this document via the
> `Roadmap-Phase:` trailer in its body (ADR-005 §2.5,
> [AGENTS.md §4.7.3](../../AGENTS.md) condition 6, Rule 08 §4).
>
> Phases follow the established `3A..3E` naming from `project-timeline.md`.
> Platform hardening continues in `3F..3M`; clinical domain begins at
> `4A`; interop at `5A`; DPC productization at `6A`; niche expansion
> from Phase 9.
>
> Changes to phase entry/exit criteria, phase order, phase non-goals,
> or the set of declared phases are **Tier 3** per ADR-005 §2.3.
> Adding a note to a phase row, refreshing a review date, or
> clarifying prose without changing a phase's declared boundaries is
> Tier 2.

---

## Legend

- **Status** — `landed` · `in progress` · `next` · `queued` · `deferred`
- **Tier default** — the baseline ADR-004 tier for slices in this phase
- **Exit** — the concrete condition that must be met to declare the
  phase closed. A phase is **not** closed by "everything feels done";
  it is closed when its exit condition is met and a slice documents
  closure (commit body cites "closes Phase N" or equivalent).
- **Dependencies** — phases that MUST have closed before this phase
  can begin
- **Non-goals** — explicit exclusions to prevent scope drift

---

## Phase 3E — Runtime datasource role switch *(landed: `e690275`)*

**Exit:** Application runs as `medcore_app` (non-superuser). Startup
refuses to boot if connected as a superuser. RLS policies from V8 enforce
at runtime against live HTTP traffic. Verify-only password posture by
default; opt-in `ALTER ROLE` sync for local/test only.

**Artifacts:** `V10__runtime_role_grants.sql`, `MedcoreAppPasswordSync`,
`DatabaseRoleSafetyCheck`, `TestcontainersConfiguration` with three
named datasources, 74/74 tests.

**Carry-forward from 3E:**
- Flyway-in-process residual (migrator credentials in JVM Hikari pool)
  tracked to **3F / 3H**.
- `medcore_migrator` as a distinct provisioned production role
  tracked to **3H**.
- Password rotation as a first-class flow tracked to **3H**.

---

## Phase 3F — Observability spine *(next)*

**Goal:** Establish request-id generation, structured logging,
OpenTelemetry traces/metrics, and health/readiness probes so that
every subsequent slice is debuggable and audit-correlatable.

**Tier default:** Tier 2 (platform code, no new auth surface).

**Entry:** 3E landed. Tests green.

**Exit:**
- Every inbound HTTP request carries a `request_id` (generated if
  absent, propagated if present; format constrained).
- All application logs are structured (JSON) and include
  `request_id`, `tenant_id` (where known), `user_id` (where known).
- OpenTelemetry SDK configured; traces emit for every HTTP request
  and every DB call; metrics emit for request latency, error rate,
  and audit-write latency.
- `/actuator/health` and `/actuator/info` (or equivalent) behind
  auth-optional read; `/actuator/health/liveness` and
  `/actuator/health/readiness` correct under datasource and
  flyway-migration states.
- Audit events correlate to request via `request_id` (ADR-003 §7
  already carries the column; 3F makes it populated end-to-end).
- Closes the central request-ID generator carry-forward from 3C.
- Closes the proxy-aware `client_ip` extraction carry-forward from
  3C.

**Dependencies:** 3E.

**Non-goals:**
- No log aggregation / shipping infrastructure (Datadog, Loki,
  CloudWatch Logs agent) in 3F. SDK-level emission only.
- No alerting or SLO definition.
- No PHI in logs — reinforced by PHI-exposure review in slice.

---

## Phase 3G — Error standardization *(queued)*

**Goal:** Unified error envelope across every API surface; consistent
mapping of 401/403/404/409/422/5xx; no internal-logic leakage.

**Tier default:** Tier 2.

**Entry:** 3F landed.

**Exit:**
- Single `ErrorResponse` DTO shape used by every controller.
- `@ControllerAdvice` mapping exhaustive for Spring Security
  AuthenticationException, AccessDeniedException, validation
  failures (400/422), missing resources (404), optimistic-lock /
  idempotency conflicts (409), and uncaught exceptions (500).
- `TenantContextMissingException` mapped (closes 3B.1
  carry-forward).
- Uniform 401 envelope (closes 3B.1 carry-forward).
- No stack traces, no SQL snippets, no class names in error
  responses. Error `code` values are a closed enum.
- Audit event emitted for every 4xx auth/authz failure.

**Dependencies:** 3F.

**Non-goals:**
- No i18n of error messages.
- No client-side error UX (frontend phase).

---

## Phase 3H — Secrets + production posture *(queued)*

**Goal:** Eliminate the Flyway-in-process residual and move all
secret material to a managed secrets store.

**Tier default:** Tier 3 (secrets handling).

**Entry:** 3G landed.

**Exit:**
- Flyway runs out-of-process in the production deployment model —
  typically as a separate one-shot task that migrates the database
  ahead of application start. Application JVM never holds migrator
  credentials.
- `MEDCORE_DB_APP_PASSWORD` sourced from AWS Secrets Manager (or
  equivalent) in production; local/dev uses env vars.
- Password rotation runbook exists and has been rehearsed once
  against local Postgres.
- `medcore_migrator` exists as a distinct provisioned role (not
  the container superuser). Runbook documents its creation.
- Closes the 3E carry-forward items.

**Dependencies:** 3F. (3G can close in parallel.)

**Non-goals:**
- No live production infrastructure (that's 3I).
- No Hashicorp Vault — AWS Secrets Manager is the target per
  `00-vision.md` §6.3.

---

## Phase 3J — WriteGate + tenancy writes + RBAC *(next)*

**Goal:** Establish `WriteGate` as Medcore's single mutation
contract, land role-derived authorities, and ship tenancy admin
writes through the framework with RLS write policies as the DB
safety net. **Reordered before 3I per ADR-007:** mutation
correctness precedes deployment readiness — writing real data
through a governed boundary is a harder gate than hosting that
data on cloud infrastructure.

**Tier default:** Tier 3 (authorization + RLS policies + new
mutation architecture).

**Entry:** 3H landed.

**Exit:**
- **Phase 3J.1 (platform substrate):** `WriteGate` pipeline
  (validate → authorize → transact-open → apply → audit-success →
  transact-close) owns the transaction boundary; `WriteContext`
  with shape-only idempotency slot; `MedcoreAuthority` 7-entry
  closed enum (`TENANT_READ/UPDATE/DELETE`,
  `MEMBERSHIP_READ/INVITE/REMOVE`, `SYSTEM_WRITE`); locked role →
  authority map (OWNER full, ADMIN no-DELETE, MEMBER read-only);
  tenant-scoped `AuthorityResolver`; V12 RLS write policies on
  `tenancy.tenant` + `tenancy.tenant_membership`;
  `tenancy.bootstrap_create_tenant` SECURITY DEFINER function
  granted to `medcore_migrator` only; `AUTHZ_WRITE_DENIED` audit
  action; ADR-007 accepted.
- **Phase 3J.2 (first endpoint):** `PATCH /api/v1/tenants/{slug}`
  (display_name update) lands through the framework as the
  golden-path tenancy write; subsequent 3J.N slices add remaining
  endpoints (`POST /tenants`, membership invite, membership
  remove, membership role update) using the same contract.
- Every write emits an audit event with `intent:<command-slug>` in
  the `reason` field (coarse action + fine intent together).
- Every denial emits an `AUTHZ_WRITE_DENIED` audit event with a
  closed-enum `WriteDenialReason` code — no command payload, no
  row content.
- Integration tests verify RLS writes refuse cross-tenant writes
  even when application-layer authz is bypassed.

**Dependencies:** 3H.

**Non-goals:**
- No self-service tenant signup UX (later product phase).
- No billing integration (Phase 6A).
- No idempotency-key deduplication in 3J — shape-only slot on
  `WriteContext`; dedupe logic lands with Phase 4A+ idempotent
  flows.
- No `SYSTEM_WRITE` callers in 3J — reserved for future backfills
  / reconciliation jobs behind an ADR.

---

## Phase 3I — Deployment baseline + CI enforcement *(queued)*

**Goal:** First real deployment substrate and machine-enforced CI
gates that make the governance discipline automatic.

**Tier default:** Tier 3 (infra/Terraform is always Tier 3).

**Entry:** 3J landed.

**Exit:**
- `infra/terraform/` contains a working `dev` environment baseline:
  VPC, ECS Fargate service, ALB, RDS Postgres (encrypted, backups),
  S3 (private), CloudFront, IAM, Secrets Manager, CloudWatch log
  groups. Staging baseline exists but may be dormant.
- Application builds a container image, pushes to ECR, deploys to
  ECS via GitHub Actions on merge to `main`.
- `.github/workflows/ci.yml` enforces: `./gradlew test`, `./gradlew
  ktlintCheck`, `./gradlew detekt`, Flyway migration validate,
  Gitleaks, `pnpm typecheck`, `pnpm test`, ArchUnit/Konsist module
  boundary tests.
- CI check fails any PR missing the `Roadmap-Phase:` trailer.
- CI check fails any PR where a `docs/product/` file has a passed
  `next_review:` date without a `Review-Deferred:` in the commit.
- The `dev` environment runs 24/7 with synthetic data only; no
  production data flows into it.

**Dependencies:** 3J.

**Non-goals:**
- No production environment (pre-pilot; no real customer).
- No blue/green or canary deployment — single rolling ECS deploy.
- No Kubernetes (EKS) — see `00-vision.md` §6.3.
- No multi-region.

---

## Phase 3K — Production IdP decision + integration *(queued)*

**Goal:** Close the production IdP ADR carry-forward from 3A.3.
Decide, document, and integrate.

**Tier default:** Tier 3 (authentication).

**Entry:** 3I landed.

**Exit:**
- ADR-006 (or next-available number) accepted, naming the
  production IdP (candidates: AWS Cognito, Auth0, Okta, Clerk,
  WorkOS — decision deferred to that ADR).
- Integration operational in `dev`. `mock-oauth2-server` remains
  for local dev only.
- Role-to-IdP-group mapping documented and wired.
- MFA requirement verified for the chosen IdP.

**Dependencies:** 3I.

**Non-goals:**
- No SCIM provisioning unless the chosen IdP makes it cheap.
- No federated SSO to customer IdPs — deferred to a later niche
  phase.

---

## Phase 3L — UX foundation *(queued)*

**Goal:** Establish the frontend foundation so Phase 4A has a
cohesive substrate to build on.

**Tier default:** Tier 2 (frontend, no security-sensitive paths).

**Entry:** 3I landed (CI and deployment in place). May run in
parallel with 3K.

**Exit:**
- `apps/web` wired with Tailwind, Radix UI primitives, TanStack
  Router, TanStack Query, Zod, React Hook Form, Storybook.
- Design-system v0 lives in `packages/ui/`: colors, spacing,
  typography, density modes (clinician dense vs patient regular),
  six core components (Button, Input, Select, Checkbox, Dialog,
  Table) with Storybook coverage.
- Accessibility harness: axe-core in Vitest, WCAG 2.1 AA baseline
  per `06-ux-and-workflow.md`.
- Workflow-metric instrumentation library: a `useMetric` hook and
  a dev-only in-browser HUD that displays timings for the
  DoD-tracked workflows. Piped to telemetry in Phase 4A.
- Generated `packages/api-client` from the OpenAPI specs (closes
  3A.3 carry-forward).

**Dependencies:** 3I.

**Non-goals:**
- No real clinical screens yet (4A+).
- No mobile app (mobile-web in 6C if needed).

---

## Phase 3M — Reference data + synthetic data *(queued)*

**Goal:** Load clinical reference data (SNOMED CT, LOINC, RxNorm,
ICD-10, NDC) and synthetic patient data so Phase 4 has real
terminology to work with.

**Tier default:** Tier 2 for data loading; Tier 3 for the CPT
licensing ADR if CPT is scoped.

**Entry:** 3I landed. Can run in parallel with 3L.

**Exit:**
- UMLS account active; SNOMED CT US Edition loaded into Postgres
  `reference` schema.
- LOINC loaded. RxNorm loaded. ICD-10-CM loaded. NDC Directory
  loaded. All with version metadata.
- CPT licensing decision documented as its own ADR (scope, cost,
  timing). If Medcore does not yet need CPT codes (pre-claims
  phase), the ADR defers licensing to Phase 10.
- Synthea-generated synthetic patient data for `dev` fixtures
  (100 patients covering the ambulatory use cases 4A–4G will
  exercise). `scripts/seed-synthetic-data.sh` runs idempotently
  against `dev`.
- Reference-data schema has RLS policy `FOR SELECT` for
  `medcore_app` (reference data is shared, not tenant-scoped).

**Dependencies:** 3I.

**Non-goals:**
- No search/indexing over reference data (later, when a real UI
  demands it).
- No FHIR CodeSystem/ValueSet wrapping (Phase 5A).

---

## Phase 4A — Patient registry *(queued)*

**Goal:** First clinical module. Patient demographics, identifiers,
merge/duplicate, address history.

**Tier default:** Tier 3 (PHI handling).

**Entry:** 3J, 3K, 3L, 3M all landed.

**Exit:**
- `patient` module: entity, repository, service, API, tests.
- Demographics per US Core Patient profile: name (with
  parts/periods), gender identity / administrative sex /
  pronouns separated; DOB; race/ethnicity (OMB categories);
  preferred language; communication preferences.
- Identifiers: MRN (generated), external IDs (multiple, typed).
- Merge/duplicate: two patients can be merged with audit;
  merged-patient lookup routes forward.
- Address and contact history preserved (not overwritten).
- FHIR `Patient` read endpoint (GET
  `/fhir/r4/Patient/{id}`) operational for single-patient read.
  Full FHIR surface lands in Phase 5A.
- Patient-creation workflow meets DoD: **≤ 10s** from dashboard
  to saved record.
- Every read/write emits audit.
- Phase 4A PHI-exposure review in `docs/security/`.

**Dependencies:** 3J, 3K, 3L, 3M.

**Non-goals:**
- No patient portal (6B).
- No insurance capture (clinic niche, Phase 10).
- No consent management UI (defensible in Phase 7+ with compliance
  maturity phase B).

---

## Phase 4B — Scheduling *(queued)*

**Goal:** Appointments, providers, locations, lifecycle.

**Tier default:** Tier 3 (PHI handling).

**Entry:** 4A landed.

**Exit:**
- `scheduling` module with appointment, provider, location,
  schedule-template entities.
- Create / reschedule / cancel / no-show flows.
- Provider-schedule templates (recurring availability) and
  overrides.
- FHIR `Appointment`, `Slot` read endpoints.
- Appointment-creation workflow meets DoD: **≤ 3 clicks** from
  patient chart to appointment created.

**Dependencies:** 4A.

**Non-goals:**
- No multi-location routing optimization.
- No patient self-scheduling (6B).
- No telehealth video linkage (5D or 9).

---

## Phase 4C — Encounter shell *(queued)*

**Goal:** Encounter lifecycle. Status machine. Provider attribution.

**Tier default:** Tier 3 (PHI).

**Entry:** 4B landed.

**Exit:**
- `encounter` module: states (planned → in-progress → finished →
  cancelled / entered-in-error). State transitions are audited.
- Attribution: primary provider, other participants.
- Linkage to patient, appointment (if any), location.
- FHIR `Encounter` read endpoint.
- Start-encounter workflow meets DoD: **≤ 3 clicks** from today's
  schedule view to encounter started.

**Dependencies:** 4B.

**Non-goals:**
- No clinical content yet (4D).
- No billing linkage (Phase 10).

---

## Phase 4D — Clinical notes *(queued)*

**Goal:** Structured + free-text clinical documentation. Signing.
Amendments (versioned, never mutated).

**Tier default:** Tier 3 (PHI).

**Entry:** 4C landed.

**Exit:**
- `clinicaldocs` module: note entity, note-template entity,
  amendment entity.
- Templates: at minimum SOAP, H&P, progress note, procedure note.
- Sections: subjective, objective, assessment, plan, ROS, PE, HPI
  — structured where a FHIR mapping exists, free-text otherwise.
- Sign flow: signed notes are immutable; amendments are new
  records linked to the original with an `amends` reference.
- FHIR `DocumentReference` surface (via 5A).
- Complete-basic-visit-note workflow meets DoD: **≤ 90s** from
  encounter-started to note-ready-to-sign on a simple ambulatory
  visit.
- Sign-note workflow meets DoD: **≤ 1 click** from
  ready-to-sign state to signed.
- Voice/ambient documentation integration NOT required for 4D;
  UX affords a hook point for 6C.

**Dependencies:** 4C.

**Non-goals:**
- No auto-coding, no suggestion engine (CDS adjacency; Phase 7+).
- No co-signer workflows initially (deferred to a later slice
  within 4D if requested by pilot).

---

## Phase 4E — Medications, allergies, problems, vitals *(queued)*

**Goal:** Core clinical data model. FHIR-aligned. CDS-ready (data
shape suitable for CDS hooks later).

**Tier default:** Tier 3 (PHI).

**Entry:** 4D landed.

**Exit:**
- `meds` module: MedicationStatement (what the patient is taking),
  MedicationRequest (what the provider ordered), MedicationDispense
  not yet. Codings via RxNorm.
- `allergies` module: AllergyIntolerance with substance (RxNorm,
  SNOMED), reaction, severity.
- `problems` module: Condition (active problem list, medical
  history). SNOMED codings.
- `vitals` module: Observation for blood pressure, heart rate,
  temperature, respiration, SpO2, height, weight, BMI. LOINC
  codings.
- Refill-medication workflow meets DoD: **≤ 15s** from patient
  chart to refill queued.
- FHIR read endpoints for all four resources.

**Dependencies:** 4D, 3M (reference data).

**Non-goals:**
- No drug-drug interaction checking (CDS / Phase 7+).
- No e-prescribing (Phase 9).
- No lab-values trending UI (later).

---

## Phase 4F — Orders + results shell *(queued)*

**Goal:** Lab/diagnostic order lifecycle. Results model. No real
LIS integration yet — stub the interop surface so Phase 9+ can
plug in.

**Tier default:** Tier 3 (PHI).

**Entry:** 4E landed.

**Exit:**
- `orders` module: ServiceRequest with lifecycle (draft → active
  → completed | revoked | entered-in-error).
- `results` module: Observation / DiagnosticReport. Ingestion
  accepts HL7v2 ORU or FHIR Observation POST (authenticated
  service account) — stubbed accepting a file drop in `dev`.
- FHIR read endpoints for ServiceRequest, DiagnosticReport.

**Dependencies:** 4E.

**Non-goals:**
- No real LIS integration (Quest, LabCorp, regional labs) —
  Phase 10+.
- No radiology PACS integration.
- No pathology.

---

## Phase 4G — Document storage *(queued)*

**Goal:** Secure upload/download of clinical documents (consent
forms, uploaded PDFs, scanned documents).

**Tier default:** Tier 3 (PHI).

**Entry:** 4F landed.

**Exit:**
- `documents` module: metadata entity, S3 key, content-type, size,
  hash, retention class.
- Signed upload URLs (pre-signed S3 POST) with size/type limits.
- Signed download URLs with short TTL.
- Retention hooks: a document marked for retention class X has a
  lifecycle policy in S3 matching the class.
- FHIR `DocumentReference` read endpoint.
- Every upload/download emits audit.

**Dependencies:** 4F, 3I (S3 infra).

**Non-goals:**
- No OCR or content extraction (later, if warranted).
- No document-signing workflow (adjacency; deferred).

---

## Phase 5A — FHIR R4 + US Core read + SMART 2.x *(queued)*

**Goal:** Full FHIR read surface for every v1 resource; SMART App
Launch 2.x authorization framework for third-party apps.

**Tier default:** Tier 3 (FHIR contracts + authorization).

**Entry:** 4G landed.

**Exit:**
- FHIR R4 read for: Patient, Appointment, Slot, Encounter,
  Condition, MedicationStatement, MedicationRequest,
  AllergyIntolerance, Observation, DiagnosticReport,
  ServiceRequest, DocumentReference. US Core profiles applied.
- SMART App Launch 2.x: EHR-launch and standalone-launch flows.
  Scopes honored. `launch`, `patient/*.read`, `user/*.read`,
  `fhirUser`, `openid`, `online_access`.
- Inferno touchstone test suite run in CI on the `dev`
  environment. Conformance results tracked.

**Dependencies:** 4G.

**Non-goals:**
- No write operations on FHIR surface yet (5B).
- No FHIR SEARCH _chain / _include / _revinclude beyond US Core
  minimums.

---

## Phase 5B — FHIR write + Bulk Data *(queued)*

**Goal:** FHIR write where applicable; Bulk Data `$export` so
customers can get their data out.

**Tier default:** Tier 3 (FHIR + PHI + authorization).

**Entry:** 5A landed.

**Exit:**
- FHIR PUT for Patient (demographics update), Appointment
  (status/reschedule), Observation (vitals). Create via POST
  only for Observation (narrow scope).
- Bulk Data `$export` per FHIR R4 Bulk Data Access IG. Async
  ndjson production to S3. Signed download URLs returned.
- All writes produce audit events carrying resource type, id,
  version.

**Dependencies:** 5A.

**Non-goals:**
- No `$import` yet.
- No FHIR Subscriptions.

---

## Phase 5C — USCDI v3 alignment *(queued)*

**Goal:** Conformance evidence for USCDI v3 data classes.

**Tier default:** Tier 3.

**Entry:** 5B landed.

**Exit:**
- USCDI v3 data-class coverage matrix documented in
  `05-interop-and-reference-data.md` with per-class mapping.
- Inferno conformance run covering relevant (g)(10)-style
  scenarios — **without** claiming certification. Results
  published internally; posture is "conformance-tested," not
  "certified."

**Dependencies:** 5B.

**Non-goals:**
- No ONC certification pursuit (Phase 11+).
- No CEHRT attestation paperwork.

---

## Phase 5D — Telehealth-lite accelerator *(queued)*

**Goal:** Minimum-viable telehealth surface to unlock a faster
first paying pilot before full DPC build-out. Not the strategic
telehealth niche (that's Phase 9) — this is a pilot accelerator.

**Tier default:** Tier 2 (mostly non-Tier-3 UX + integration).

**Entry:** 5C landed. MAY begin in parallel with late-stage 5B if
DPC pilot signal is weak.

**Exit:**
- Appointment type "Telehealth" with an async pre-visit
  questionnaire (structured).
- Encounter documentation flow identical to in-person (no new
  note type).
- Video: Twilio or Daily.co session created per appointment,
  linked via an opaque session URL. Video happens in a third-
  party service; Medcore stores no recordings.
- Messaging: simple async patient → provider and provider →
  patient threaded messages, linked to patient/encounter.
- FHIR Encounter has `virtual` flag set appropriately.

**Dependencies:** 5C.

**Non-goals:**
- No e-prescribing (Surescripts) — that's Phase 9.
- No EPCS (controlled substance e-Rx) — Phase 9+.
- No state-licensure tracking or cross-state telehealth
  registration — Phase 9.
- No recording/transcription — explicit PHI-minimization call.

---

## Phase 6A — Subscription billing (Stripe) *(queued — DPC)*

**Goal:** Stripe integration for DPC membership tiers, recurring
billing, dunning, portal.

**Tier default:** Tier 3 (payment data boundary; PCI scope
avoidance is itself a posture claim).

**Entry:** 5D landed OR 5C landed if telehealth-lite accelerator
is not taken.

**Exit:**
- Stripe Products + Prices modeled as `membership_plan` entities
  in Medcore. Per-tenant active plan set.
- Patient subscription lifecycle: create, upgrade, downgrade,
  cancel. Dunning on failed payments.
- Stripe webhook consumer in a dedicated handler with signature
  verification. Events persisted to audit.
- Patient billing portal link (hosted by Stripe) surfaced in the
  patient-facing experience.
- Zero cardholder data is stored in Medcore. Posture statement
  lands in `04-compliance-and-legal.md`.

**Dependencies:** 5D or 5C.

**Non-goals:**
- No insurance claims — clinic niche.
- No RCM — clinic niche.

---

## Phase 6B — Patient-facing portal *(queued — DPC)*

**Goal:** Patient-facing web UI for scheduling, messaging, bill
pay, record access.

**Tier default:** Tier 3 (patient-authenticated surface).

**Entry:** 6A landed.

**Exit:**
- Patient portal at `portal.<tenant>.medcore.app` (or equivalent).
- Separate auth posture: patient OIDC with MFA optional. Distinct
  IdP configuration from workforce IdP per ADR-002.
- Features: upcoming appointments, schedule new appointment from
  provider availability, send/receive messages, pay bill (Stripe
  portal link), download records (FHIR `$export` triggered
  server-side, S3-signed download URL returned).
- Accessibility: WCAG 2.1 AA verified.

**Dependencies:** 6A, 5A (FHIR read for record access).

**Non-goals:**
- No patient-to-patient features.
- No third-party health-data importing.

---

## Phase 6C — Clinician mobile web *(queued — DPC)*

**Goal:** Mobile-web optimized clinician surface for note entry.
Voice-capture hook stub for future ambient documentation.

**Tier default:** Tier 2 (frontend, reuses 3L infrastructure).

**Entry:** 6B landed.

**Exit:**
- `apps/web` responsive layouts tested at mobile breakpoints for
  patient chart, encounter start, note entry, sign.
- Offline-tolerant note draft: if network drops mid-note, draft
  persists locally and syncs on reconnect. Never loses content.
- Voice-capture hook: UI affordance for "dictate" that captures
  audio via MediaRecorder API, attaches to the note as an audio
  file. No transcription in 6C. Phase 7+ / 9+ may wire an
  ambient-documentation vendor (Abridge, Nuance DAX, Suki) via
  MCP — explicit non-goal for 6C.

**Dependencies:** 6B.

**Non-goals:**
- No native iOS/Android app.
- No transcription (that's a vendor integration).
- No voice-controlled UI beyond "start/stop dictation".

---

## Phase 6D — Pilot readiness + first pilot onboarding *(queued — DPC)*

**Goal:** First real DPC practice signs a BAA, onboards to
Medcore, and operates a complete billing cycle.

**Tier default:** Tier 3 (cross-cutting; compliance, legal,
production).

**Entry:** 6C landed. Compliance maturity Phase B achieved (see
`04-compliance-and-legal.md`).

**Exit:**
- Production environment live in AWS (Terraform-provisioned,
  change-managed).
- BAA executed with the pilot practice. Template in `docs/legal/`.
- Pilot onboarding runbook rehearsed (`docs/runbooks/`).
- At least one billing cycle (typically 30 days for DPC monthly
  subscription) completed with the pilot practice using Medcore
  as the sole EHR.
- Zero PHI incidents during the cycle.
- Success criteria from `00-vision.md` §7 met.

**Dependencies:** 6C, compliance Phase B.

**Non-goals:**
- No scaling to multiple pilots yet — focus on learning from
  one.

---

## Phase 7 — Compliance operationalization *(queued)*

**Goal:** Move from compliance maturity Phase B (operational-ready)
to Phase C (market-ready). SOC 2 Type I readiness. Formal
clinical-safety governance. Introduce hazard log and safety case.

**Tier default:** Tier 3.

**Entry:** 6D landed. Real customer operating Medcore.

**Exit:**
- SOC 2 Type I readiness assessment complete (external
  assessor). Any gaps tracked to remediation.
- Clinical safety case document drafted per the conventions in
  `04-compliance-and-legal.md`.
- Hazard log live; every Phase 7+ slice reviews the hazard log.
- Incident response plan rehearsed.
- Access review cadence operational.
- DR test performed and documented.

**Dependencies:** 6D.

**Non-goals:**
- No HITRUST pursuit in 7.
- No Type II attestation (that's Phase 8's residency period).

---

## Phase 8 — External pentest + hardening *(queued)*

**Goal:** Third-party penetration test; remediate findings.

**Tier default:** Tier 3.

**Entry:** 7 landed.

**Exit:**
- External pentest executed. Report received. Findings triaged
  by severity with remediation plan.
- All high/critical findings remediated.
- Trust-center public page lives describing Medcore's security
  posture, updated from §5 of `00-vision.md`.

**Dependencies:** 7.

**Non-goals:**
- No red-team engagement — out of scope for pentest.

---

## Phase 9 — Telehealth niche (full) *(queued)*

**Goal:** Expand from telehealth-lite accelerator to full
telehealth niche. Video service integration, e-prescribing
(Surescripts), state-licensure tracking.

**Tier default:** Tier 3.

**Entry:** 8 landed.

**Exit:**
- Surescripts NewRx, RxChange, RxRenewal, RxFill integrated.
  **NOT** EPCS (deferred).
- Real video integration (Twilio Programmable Video or Zoom
  Video SDK) with waiting-room, session recording (opt-in per
  jurisdiction), consent capture.
- State-licensure registry: per-provider licenses with issuing
  state, status, expiration. UI gates scheduling by
  patient-state / provider-license match.
- Three telehealth practices onboarded.

**Dependencies:** 8.

**Non-goals:**
- EPCS (controlled substance e-Rx) — deferred to 9-follow-on
  with its own ADR; requires 21 CFR 1311 third-party
  certification.
- Multi-jurisdiction telehealth (cross-border) — scope-limited
  to US at Phase 9.

---

## Phase 10 — Clinics niche *(queued)*

**Goal:** Claim generation (837), remittance (835), eligibility
(270/271), prior authorization shell.

**Tier default:** Tier 3.

**Entry:** 9 landed.

**Exit:**
- Clearinghouse integration (typically Change Healthcare, Waystar,
  Availity — specific vendor chosen in an ADR).
- 837 Professional claim generation from Medcore encounters.
- 835 ERA ingestion and posting.
- 270/271 eligibility check pre-appointment.
- CPT code support (licensing decision from 3M revisited and
  executed).
- At least one non-DPC ambulatory clinic pilot signed.

**Dependencies:** 9.

**Non-goals:**
- No institutional claims (837I) — hospital niche.
- No NCPDP SCRIPT ePA until a specific pilot customer requires it.

---

## Phase 11 — ONC certification (conditional) *(deferred)*

**Goal:** Pursue ONC Health IT certification if the market
position at Phase 10 warrants it. **Conditional phase:** entry
requires an explicit ADR declaring "pursue ONC."

**Tier default:** Tier 3.

**Entry:** 10 landed AND ADR declaring ONC pursuit accepted.

**Exit:**
- ONC-ATL engaged, test procedures executed.
- (g)(10) Standardized API criterion met (FHIR / US Core / SMART
  /OAuth — mostly existing Phase 5A–5C work).
- CEHRT attestation filed.

**Dependencies:** 10, conditional ADR.

**Non-goals:**
- ONC is conditional — Medcore may never enter this phase if the
  business case doesn't materialize.

---

## Phase 12 — Hospital niche *(deferred)*

**Goal:** HL7v2 ADT/ORM/ORU interfaces. Inpatient workflows.

**Tier default:** Tier 3.

**Entry:** 11 landed OR 10 landed + explicit "skip ONC" ADR.

**Exit:** scoped when the phase becomes real.

**Non-goals:** scoped when the phase becomes real.

---

## Cross-phase carry-forward ledger

Items the roadmap inherits from Phases 0–3E and where they close:

| Item | Originated | Closes in |
| ---- | ---- | ---- |
| Central request-ID generator | 3C | **3F** |
| Proxy-aware `client_ip` extraction | 3C | **3F** |
| Uniform 401 envelope | 3B.1 | **3G** |
| `TenantContextMissingException` HTTP mapping | 3B.1 | **3G** |
| Flyway out-of-process | 3E | **3H** |
| Password rotation flow | 3E | **3H** |
| `medcore_migrator` distinct role | 3E | **3H** |
| Concurrent first-login retry | 3A.3 | **4A** (patient-create adjacency) |
| Role → authority mapping | 3A.3 | **3J** |
| Generated `packages/api-client` | 3A.3 | **3L** |
| Production IdP ADR | 3A.3 | **3K.1** (closed — ADR-008 locks WorkOS + identity contract + Medcore-status-authoritative invariant) |
| RLS policies for tenancy writes | 3D | **3J** (closed in 3J.1 via V12) |
| Per-tenant chain sharding | 3D | evaluated at **5A** (may stay deferred) |
| Chain verification endpoint / scheduled job | 3D | **3F** (job) + **3J** (operator surface) |
| `MEMBERSHIP_ROLE_UPDATE` authority + test + map entry | 3J.1 | **3J.N** (first role-change endpoint) |
| `WriteResponse` envelope extensibility (typed fields only, no `Any`-map) | 3J.1 | **3J.N or 4A** (first caller that needs metadata) |
| `AuthorityResolver` caching strategy ADR | 3J.1 | **4A+** (when traffic shape warrants) |
| `WriteContext.idempotencyKey` dedupe persistence + replay semantics | 3J.1 | **4A** (patient-create) / **6A** (Stripe webhooks) |
| V13+ SECURITY DEFINER admin-read helper (partial close — `caller_is_tenant_admin` shipped in V13; broader `resolve_authority` deferred) | 3J.2 | partial **3J.N** (V13) + remainder carried for Phase 4A if broader resolver needed |
| MEMBERSHIP_SUSPENDED → NOT_A_MEMBER collapse at tenant-SELECT layer (V8's tenant policy, distinct from V13's membership expansion) | 3J.2 | **Phase 4A+** (when caller-suspended visibility matters for clinical surfaces) |
| Audit payload column / structured mutation diff (before/after) | 3J.2 | **Phase 7** (compliance-driven ADR) |
| `If-Match` precondition header on PATCH | 3J.2 | **3J.N or 3L** (when a client demands it) |
| ArchUnit rule: WriteGate is exclusive mutation entry point | 3J.2 | **3I.1** (closed — 12 rules landed) |
| `PhiRlsTxHook` sibling that sets `app.current_tenant_id` | 3J.2 | **4A.0** (closed — full PHI RLS substrate shipped: PhiRequestContextHolder + Filter + SessionContext + RlsTxHook + ArchUnit Rule 13) |
| `MEMBERSHIP_ROLE_UPDATE` authority + promote/demote endpoint | 3J.3 | **3J.N** (closed) |
| `DELETE /memberships/{id}` member-removal endpoint | 3J.3 | **3J.N** (closed) |
| Custom JSON deserialiser for `MembershipRole` that maps invalid values to 422 (not 400) | 3J.3 | future minor hardening slice |
| `actor_role` snapshot column on `audit.audit_event` — capture caller's role at audit time to avoid historical-join drift on `tenant_membership` role changes | 3J.3 | **Phase 7** (bundled with the audit-payload-diff schema evolution ADR) |
| Deferred CHECK trigger at commit time for last-OWNER invariant (closes phantom-INSERT window of pessimistic-lock approach) | 3J.N | **Phase 7 or earlier** if needed |
| Structured `from_role`/`to_role` audit columns for membership role changes (currently encoded in `reason` as closed-enum tokens) | 3J.N | **Phase 7** (audit-schema-evolution ADR) |
| ArchUnit Rule 13 `.allowEmptyShould(true)` allowance (first `..clinical..service..` consumer required) | 4A.0 | **4A.2** (closed — `DuplicatePatientDetector` is the first `@Component`-annotated clinical service class with real `PhiSessionContext` dependency; rule narrowed to `@Component` classes to exclude exception/data class noise) |
| Rate-limiting on duplicate-patient-warning endpoint (enumeration mitigation on top of authority + minimal-disclosure gates) | 4A.2 | future hardening slice when abuse observed |
| IMPORTED MRN path + collision-retry loop against `uq_clinical_patient_tenant_mrn` | 4A.2 | when a pilot clinic with legacy data demands it |
| Real `Idempotency-Key` dedupe persistence + replay (shape-only since 3J.1; patient-create is the first consumer that could drive this) | 3J.1 / 4A.2 | **4A.2.1 hardening slice or 6A Stripe-webhook flow** |

---

*Last reviewed: 2026-04-23 (Phase 4A.2 — first REAL PHI write
path: POST + PATCH /patients through WriteGate + PhiRlsTxHook;
V15 per-tenant MRN counter with atomic upsert + rollback
safety; V16 fuzzystrmatch relocation for runtime phonetic
duplicate detection; ArchUnit Rule 13 activated; 390/390
tests green). Next review: 2026-07-25 (quarterly). Review
cadence aligned with competitive-landscape review cadence.*
