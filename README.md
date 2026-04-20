# Medcore

> **Enterprise-grade, AI-governed Electronic Health Record (EHR) platform.**

Medcore is an EHR built under an explicit governance model: every contribution
— human or AI — operates against a fixed set of architectural, security, and
compliance invariants. The governance system is defined **before** any
feature code is written.

This repository is currently in **Phase 0: Foundation**. No application
feature code exists yet. What exists is the operating contract, the rules,
and the structure under which all future work will occur.

---

## Compliance Scope

Medcore is being designed to operate under:

- **HIPAA** Privacy, Security, and Breach Notification Rules (US)
- **HITECH** Act audit and breach provisions
- **SOC 2 Type II** (security, availability, confidentiality)
- **HL7 FHIR R4** interoperability
- **21 CFR Part 11** and **GDPR** where applicable

All code MUST be written with these obligations in mind. See
[`AGENTS.md`](./AGENTS.md) for the operating contract and
[`docs/architecture/medcore_architecture_charter.md`](./docs/architecture/medcore_architecture_charter.md)
for the architecture charter.

---

## Repository Layout

| Path                  | Purpose                                                     |
| --------------------- | ----------------------------------------------------------- |
| `.github/workflows/`  | CI/CD pipelines — lint, test, security scans, policy checks |
| `.cursor/rules/`      | Cursor IDE enforcement rules                                |
| `.claude/skills/`     | Claude Code reusable procedures                             |
| `.claude/agents/`     | Claude Code subagent definitions                            |
| `docs/architecture/`  | System design, charter, C4 diagrams                         |
| `docs/adr/`           | Architecture Decision Records (immutable log)               |
| `docs/compliance/`    | HIPAA / SOC 2 / HITECH control mappings                     |
| `docs/security/`      | Threat models, STRIDE analyses, data-flow diagrams          |
| `docs/interoperability/` | FHIR profiles, HL7 mappings, terminology                 |
| `docs/runbooks/`      | On-call, incident response, recovery procedures             |
| `docs/evidence/`      | Audit artifacts for compliance attestations                 |
| `apps/web/`           | Frontend application (placeholder)                          |
| `apps/api/`           | Backend API service (placeholder)                           |
| `packages/ui/`        | Shared UI component library                                 |
| `packages/config/`    | Shared configuration (lint, tsconfig, env schema)           |
| `packages/schemas/`   | FHIR + domain schemas, OpenAPI specs, Zod / JSON Schema     |
| `packages/api-client/`| Generated typed API client                                  |
| `infra/terraform/`    | Infrastructure-as-Code (authoritative)                      |
| `scripts/`            | Developer and CI automation                                 |

---

## Governance Documents (Read First)

1. [`AGENTS.md`](./AGENTS.md) — Operating contract for humans and AI agents.
2. [`docs/architecture/medcore_architecture_charter.md`](./docs/architecture/medcore_architecture_charter.md) — Charter and invariants.
3. [`.cursor/rules/`](./.cursor/rules/) — Rule set enforced at author-time.
4. [`docs/adr/`](./docs/adr/) — Architecture Decision Records.

---

## Development Workflow

1. Read `AGENTS.md` and the relevant `.cursor/rules/*.mdc`.
2. Confirm an ADR exists for any cross-cutting or irreversible change.
3. Author contracts in `packages/schemas/` before implementation code.
4. Run `make verify` locally before every commit.
5. Open a pull request that cites the applicable invariants and ADRs.

```bash
make help        # list available targets
make verify      # run the full local gate (format, lint, test, policy)
```

---

## Status

- [x] Phase 0 — Governance and repository foundation
- [ ] Phase 1 — Identity, tenancy, and audit substrate
- [ ] Phase 2 — FHIR core resources (Patient, Encounter, Observation)
- [ ] Phase 3 — Clinical workflows
- [ ] Phase 4 — AI-assisted clinical features (under governed AI policy)

The roadmap is directional. Each phase is gated by an ADR and a compliance
review.

---

## License & Ownership

This repository is private and proprietary. Redistribution, reuse, or access
outside the authorized scope is prohibited. Internal ownership and
stewardship are documented in `docs/runbooks/ownership.md` (to be added).

---

## Contact

Governance questions and waiver requests MUST be raised as issues with the
`governance` label. Silent deviation from this repository's rules is a
compliance incident.
