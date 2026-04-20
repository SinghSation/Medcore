---
name: new-module
description: Use when creating a new module under apps/ or packages/. Walks the governance-compliant module creation sequence — schema, persistence, code, tests, audit, ownership.
---

# Skill: new-module

Follow this procedure **in order** when creating any new module (feature
service, bounded context, or shared package). Do not skip steps. Do not
reorder. If a prerequisite is missing, stop and ask.

## 0. Preconditions

- [ ] An issue or user instruction authorizes this module.
- [ ] The module fits an existing top-level directory. If it does not, stop
      and write an ADR proposing a new top-level directory before continuing.
- [ ] The owner is identified. Modules without a named owner MUST NOT be
      created.

## 1. Scope the module

Write a one-paragraph scope statement answering:

- What responsibility does this module own?
- What does it explicitly **not** own?
- Which adjacent modules does it depend on, and through which contracts?
- What data classification does it handle (PHI / PII / Confidential /
  Internal / Public)?

Save this paragraph as the top section of the module's `README.md`.

## 2. Decide on an ADR

If the module:

- Introduces a new trust boundary, or
- Changes data classification, or
- Adds a new runtime dependency of material footprint, or
- Creates a new persistence store,

…then open an ADR (`make adr-new TITLE="introduce-<module>"`) and attach it
to the same PR.

## 3. Create the contract first

Before writing any code:

1. Add the module's schemas to `packages/schemas/`:
   - OpenAPI spec under `packages/schemas/openapi/<module>/`.
   - FHIR profiles under `packages/schemas/fhir/<module>/` if clinical.
   - Domain types / Zod / JSON Schema as appropriate.
2. Regenerate `packages/api-client/` so the typed client exists.
3. Commit the contract and generated artifacts as a discrete preparation
   step that stands on its own.

## 4. Scaffold the module directory

Minimum layout:

```
<app-or-package>/<module>/
  README.md           # scope, boundaries, owner, runbook link
  src/                # source code
  migrations/         # if the module owns persistence
  tests/
    unit/
    integration/
    contract/
  module.ownership    # single-line owner identifier
```

The README MUST include:

- Scope statement (from step 1).
- Public contract reference (schema path).
- Audit surface (what events this module emits).
- Failure mode summary.
- Link to runbook under `docs/runbooks/`.

## 5. Persistence (if applicable)

Follow `.claude/skills/safe-db-migration.md`. In summary:

- Declare `id`, `tenant_id`, `created_at/by`, `updated_at/by`, `deleted_at`,
  and a row version column where concurrent edits are possible.
- Add indexes for every foreign key and every hot-path filter column.
- Enable row-level security / tenancy enforcement at the DB layer.
- Declare retention under `docs/compliance/retention.md`.

## 6. Implement the module

- Implement against the generated contract types. No hand-written shapes
  that duplicate the schema.
- Authorization calls go through the central policy engine. Deny-by-default.
- Every PHI read/write emits an audit event (see Rule 06).
- Structured logging only. No `console.log`.
- Outbound calls through the sanctioned client wrappers.

## 7. Tests (REQUIRED classes)

Add tests covering:

- Happy path.
- Authorization failure (unauthenticated + authenticated-but-forbidden).
- Tenant isolation.
- Input validation edges.
- Audit emission.
- Idempotency on retry.
- Downstream failure → documented error envelope.

## 8. Observability

- Declare metrics in the module's registry.
- Declare an SLO in `docs/runbooks/<module>.md`.
- Confirm the correlation / request ID flows through every emission.

## 9. Documentation

- Module `README.md` complete.
- Entry added to `docs/architecture/` index (when it exists).
- Runbook stub at `docs/runbooks/<module>.md`.
- Ownership entry in `docs/runbooks/ownership.md` (when it exists).

## 10. Acceptance

Before the PR can be merged:

- [ ] All required tests pass.
- [ ] `make verify` passes locally.
- [ ] ADR (if required) is attached.
- [ ] PHI exposure review (`.claude/skills/phi-exposure-review.md`) is
      attached to the PR.
- [ ] Owner approval recorded.

A module that is merged without satisfying this checklist is a governance
incident.
