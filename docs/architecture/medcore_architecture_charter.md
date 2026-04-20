# Medcore Architecture Charter

**Status:** Adopted — Phase 0
**Owner:** Repository owner
**Last reviewed:** Phase 0 initialization

This charter states the architectural principles, invariants, and boundaries
for Medcore. It is the second-highest-priority document in the repository,
subordinate only to applicable law and regulation. All ADRs, rules, skills,
and code MUST be consistent with this charter. Any deviation REQUIRES an
approved ADR.

---

## 1. Mission

Deliver a safe, compliant, interoperable Electronic Health Record platform
that a small team can operate responsibly at enterprise quality, using
AI-assisted development under a governed contract.

---

## 2. Non-Negotiable Principles

The following principles are invariants. They bind every component.

### 2.1 Safety First
Patient safety is the primary design constraint. Where safety conflicts with
velocity, velocity yields.

### 2.2 Privacy by Construction
PHI is handled under deny-by-default access control, end-to-end encryption,
minimum necessary disclosure, and comprehensive audit. Privacy is not a
feature layered on top — it is a property of the architecture.

### 2.3 Contract-First
Every external and internal API is specified before implementation. The
specification lives in `packages/schemas/` and is the single source of truth.
Hand-written ad-hoc endpoints are prohibited.

### 2.4 Interoperability by Default
Clinical data uses **HL7 FHIR R4** representations at rest and in motion
wherever a FHIR resource exists. Proprietary representations are permitted
only when a FHIR equivalent is absent, and MUST be documented.

### 2.5 Observability is Not Optional
Every service emits structured logs, metrics, and traces. Every PHI access
generates an audit event. An undetectable change is a non-change.

### 2.6 Reproducible Infrastructure
All infrastructure is expressed in `infra/terraform/`. Click-ops is
prohibited. Environments are recreatable from code.

### 2.7 Least Privilege
Humans, services, and AI agents receive the minimum permissions required.
Broad "admin" roles are prohibited in production paths.

### 2.8 Reversibility
Schema migrations, feature rollouts, and infrastructure changes are designed
to be reversible. Irreversible operations REQUIRE an ADR and a rehearsed
rollback plan.

---

## 3. System Context

### 3.1 Actors

- **Clinicians** — read and write clinical records within their scope.
- **Patients** — read their own records, manage consent and access.
- **Administrators** — configure the tenant, manage users, review audits.
- **Integrations** — external systems exchanging FHIR, HL7, or CSV data.
- **AI assistants** — agents operating under §6 (Governed AI).

### 3.2 Trust Boundaries

1. **Public internet ↔ edge** — WAF, DDoS mitigation, TLS termination.
2. **Edge ↔ application** — authenticated, tenant-scoped requests only.
3. **Application ↔ data** — authorized via policy engine, audited.
4. **Tenant ↔ tenant** — strict isolation; no cross-tenant reads.
5. **Application ↔ AI provider** — no PHI crosses this boundary without
   explicit, policy-gated consent and de-identification controls.

Each boundary is enforced in code, not documentation.

---

## 4. Architectural Style

Medcore is a **modular monolith with strict internal boundaries**, evolving
toward services only when scale or organizational pressure demands it.
Premature microservices are prohibited. Every internal module:

- Exposes a typed contract from `packages/schemas/`.
- Owns its own persistence; no cross-module table access.
- Emits its own audit, metric, and trace stream.
- Can be extracted to a separate service without code rewrites when needed.

This bias toward internal modularity with optional physical decomposition is
deliberate: it matches the cost-constrained, AI-assisted operating model
while preserving a credible path to future horizontal scale.

---

## 5. Data Classification

| Class                 | Examples                                         | Handling                                                          |
| --------------------- | ------------------------------------------------ | ----------------------------------------------------------------- |
| **PHI**               | Patient names, conditions, observations, notes   | Encrypted, audited, access-controlled, never logged               |
| **PII (non-PHI)**     | Staff names, emails, phone numbers               | Encrypted at rest, access-controlled                              |
| **Confidential**      | Pricing, contracts, internal policy              | Access-controlled, not in public artifacts                        |
| **Internal**          | Non-sensitive operational data                   | Authenticated access                                              |
| **Public**            | Marketing, docs, open source                     | Freely shareable                                                  |

Classification MUST be declared at schema level. Mis-classification is a
governance incident.

---

## 6. Governed AI

AI is a first-class tool in this repository and a regulated surface inside
the product.

### 6.1 AI in Development
Every AI agent operates under `AGENTS.md`. Agents MUST NOT fabricate,
bypass governance, or take irreversible actions autonomously. Reviews of
AI-generated code are held to the same bar as human code.

### 6.2 AI in Product
Any AI feature that operates on PHI REQUIRES:

1. A dedicated ADR describing purpose, data flow, and failure modes.
2. A threat model and privacy impact assessment under `docs/security/`.
3. Input/output logging under audit (with PHI-safe redaction).
4. An override and explainability path for clinicians.
5. A documented fallback when the model is unavailable or unsafe.

AI MUST NEVER be the sole authority for a clinical decision.

---

## 7. Compliance Posture

Medcore maps technical controls to regulatory obligations under
`docs/compliance/`. Every control MUST have: an owner, an evidence source, a
test procedure, and a review cadence. Controls without evidence are
considered not implemented.

---

## 8. Change Control

Architectural changes follow the ADR process under `docs/adr/`. An ADR is
REQUIRED when a change:

- Alters a trust boundary.
- Adds or removes a top-level repository directory.
- Introduces a new runtime dependency of material footprint.
- Changes data classification, retention, or residency.
- Modifies authentication, authorization, or audit semantics.
- Amends this charter or `AGENTS.md`.

ADRs are immutable once accepted. Superseding ADRs reference the prior ADR
by number.

---

## 9. Out of Scope (for now)

Explicitly out of scope until a dedicated ADR opens each area:

- Multi-region active-active deployment.
- On-premise / air-gapped hosting.
- Medical device integrations requiring FDA clearance.
- Insurance claim submission (X12, EDI).
- Genomic data at scale.

Items in this list are not forbidden forever; they are deferred until the
foundation is credible enough to absorb their complexity.

---

## 10. Acceptance

This charter is accepted as of Phase 0 and binds all subsequent work. It
will be revisited no less than quarterly and whenever a superseding ADR is
adopted.
