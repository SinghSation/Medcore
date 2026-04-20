---
name: api-contract-first
description: Use before creating or modifying any HTTP endpoint, FHIR resource, or event. Enforces contract-first authoring, schema regeneration, and contract tests.
---

# Skill: api-contract-first

Medcore's APIs are contract-first. An endpoint or event that ships before
its contract is a governance incident. This skill walks the mandatory
sequence.

## When to use

- Adding a new HTTP endpoint.
- Changing an existing endpoint's request, response, error, or auth shape.
- Adding or modifying an event schema.
- Adding or profiling a FHIR resource.
- Publishing a new client library method.

## When NOT to use

- Pure internal refactoring with no contract surface change.
- Comment-only or documentation-only edits.

## Procedure

### 1. Locate or create the contract

- Internal HTTP: `packages/schemas/openapi/<domain>.yaml`.
- Clinical resources: `packages/schemas/fhir/<resource>/` (R4 profile).
- Events: `packages/schemas/events/<topic>.json`.
- Shared types: `packages/schemas/types/`.

If the file does not exist, create it — do NOT start in the application
code.

### 2. Specify the change

For each new or modified operation, the contract MUST define:

- **Path / topic** and **method / verb**.
- **Summary** and **description** (human-readable).
- **Request**: headers, path params, query params, body schema.
- **Response(s)**: one per status code, each with a schema.
- **Error envelope**: reference the shared error schema.
- **Security**: which auth scheme, which scope / permission.
- **Idempotency key** support, if mutating.
- **Rate limiting** class (if applicable).
- **Audit surface**: which audit event this operation emits.
- **Data classification** of every field (PHI / PII / Internal / Public).

Additive changes vs. breaking changes:

- Adding an optional field, a new status code, or a new endpoint — additive.
- Removing or renaming a field, narrowing types, tightening auth, or
  changing error codes — **breaking**. Breaking changes REQUIRE a new
  version and an ADR.

### 3. Regenerate

Run the generator to produce:

- Types under `packages/api-client/src/generated/`.
- Validators (Zod / equivalent) aligned with the OpenAPI schema.
- Client methods under `packages/api-client/src/<domain>/`.

Generated output MUST be committed with the schema change. Hand-edited
generated files are prohibited.

### 4. Contract tests

Add or update contract tests:

- **Provider test** — the server implementation satisfies the schema for
  every response path.
- **Consumer test** — each consumer of the generated client compiles and
  behaves correctly under mocked responses that match the schema.
- **Schema lint** — the OpenAPI / JSON Schema / FHIR profile passes the
  configured linters (Spectral, etc.).

A CI gate MUST detect drift between the schema and the generated client.
Drift is a build failure.

### 5. Implement

Only after steps 1–4 are green, implement the server handler and the
consumer call site. Both MUST import from generated types, not redeclare
shapes inline.

### 6. Server-side responsibilities

- Validate the request body against the generated validator at the edge.
- Call the policy engine for authorization.
- Emit the audit event declared in step 2.
- Log structured events with `request_id` and `tenant_id`.
- Return only the fields declared in the response schema. Excess properties
  are a defect.

### 7. Consumer-side responsibilities

- Use the generated client method. No raw `fetch` / `axios` calls.
- Handle every documented error code.
- Do not catch-and-swallow; propagate to the boundary that can act.

### 8. Versioning & deprecation

If the change is breaking:

- Introduce the new version alongside the old.
- Emit a deprecation header on the old version.
- Track old-version usage in metrics.
- Commit an ADR that includes the sunset date and migration guide.

### 9. PR checklist

- [ ] Contract file updated in `packages/schemas/`.
- [ ] Generated artifacts regenerated and committed.
- [ ] Schema lint passes.
- [ ] Contract tests added and passing.
- [ ] Server and client implementations reference generated types.
- [ ] Audit event declared and tested.
- [ ] ADR attached (if breaking).
- [ ] PHI exposure review attached (if the payload involves PHI).

If any box is unchecked, the assistant MUST NOT mark the task complete.
