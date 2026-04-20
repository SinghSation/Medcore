# packages/schemas

Authoritative contracts for Medcore. Every API, event, and shared domain
type originates here. If a shape is not declared in this package, it is
**not** a contract.

## Scope

Expected contents (added as phases land):

- `openapi/` — OpenAPI 3.1 specifications for internal and external HTTP
  APIs, organized by domain.
- `fhir/` — HL7 FHIR R4 profiles, extensions, value sets, and
  terminology bindings for clinical resources.
- `events/` — JSON Schema (or Protobuf where justified) definitions for
  asynchronous events and queue payloads.
- `types/` — shared domain types and enumerations that cross module
  boundaries (e.g., tenant, principal, classification).
- `errors/` — the shared error envelope schema and stable error code
  catalog.
- `analytics/` — approved analytics event shapes. Events not declared here
  MUST NOT fire.

## Source of truth

- **Internal HTTP APIs** → OpenAPI 3.1 in `openapi/`.
- **Clinical resources** → FHIR R4 profiles in `fhir/`.
- **Events** → JSON Schema in `events/`.
- **Runtime validators** (Zod or equivalent) MUST be generated from, or
  align with, these schemas. They are not independent sources of truth.

Hand-written type definitions that duplicate these contracts in consuming
code are prohibited. Consumers import from `packages/api-client` or from
this package's generated output.

## Rules

1. **Contract-first.** Schemas change **before** any endpoint, handler, or
   client method that implements them. See
   `.claude/skills/api-contract-first.md`.
2. **Tagged classification.** Every field MUST declare its data
   classification (PHI / PII / Confidential / Internal / Public) in an
   agreed extension (e.g., `x-medcore-classification`). Untagged fields
   fail the schema linter.
3. **No breaking changes in place.** Breaking changes REQUIRE a new
   version and an ADR. Additive changes may ship within the current
   version if they pass the contract test gate.
4. **Linted.** Every schema file is linted by the configured tool (for
   OpenAPI: Spectral). Lint failures block CI.
5. **Tested.** Every contract has a conformance test exercising the
   provider and at least one consumer.

## FHIR specifics

- Profiles build on the published FHIR R4 base resources.
- Extensions are namespaced under a stable URL scheme and documented.
- Terminology bindings use authoritative systems (SNOMED CT, LOINC,
  RxNorm, ICD-10-CM/PCS) as appropriate to the resource.
- Custom search parameters are declared explicitly and tested.

## Versioning

- Versioned paths (`v1`, `v2`) for external HTTP APIs.
- Event schemas carry a `schema_version` field; consumers MUST tolerate
  unknown optional fields.
- FHIR profiles follow the FHIR versioning rules for extensions and
  profiles.

## Adding a schema

1. Place the schema in the correct subdirectory.
2. Tag every field's classification.
3. Run the linter and the generator (once wired).
4. Add or update contract tests.
5. Update this README if a new category of schema is introduced.

## Forbidden

- Editing generated output by hand.
- Sharing types via copy-paste into consumer code.
- Declaring a schema "optional" to avoid versioning a breaking change.
- Exposing PHI in a schema example or description.
