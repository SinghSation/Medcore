# Clinical Write Pattern — v1.1

**Status:** NORMATIVE as of Phase 4A.2 (v1.0); amended 4A.3 (v1.1).
**Reference implementations:** `com.medcore.clinical.patient.*`
— patient demographics (4A.2) + patient identifiers (4A.3). See
file map in §9.
**Scope:** every future PHI-bearing write surface (4A.5 read +
read-audit; 4B scheduling; 4C encounters; and beyond).
**Change log:** §11 tracks revisions. v1.1 lands three
clarifications driven by 4A.3 pattern-validation — no
breaking changes to v1.0 REQUIRED rules.

---

## 0. How to read this document

Every element of the pattern is classified as one of:

- **REQUIRED** — load-bearing contract. A slice that deviates from
  a REQUIRED element breaks the system's security / audit /
  compliance guarantees. ArchUnit, RLS, or runtime tests enforce
  most of them.
- **INCIDENTAL** — a specific choice made in 4A.2's implementation
  that a future slice may reasonably deviate from without breaking
  anything. Examples: a variable's name, the order of fields in a
  response DTO, a specific query strategy. INCIDENTAL decisions
  are NOT prescriptive.

Throughout §§1–8, each rule is tagged `[REQUIRED]` or
`[INCIDENTAL]`. Where the intent is REQUIRED but the specific
expression is INCIDENTAL (e.g., the class name is incidental, but
the fact that such a class exists is required), the tags separate
the two concerns.

A slice that copies 4A.2 verbatim will always pass. A slice that
follows every `[REQUIRED]` rule will also always pass, and can
deviate from `[INCIDENTAL]` specifics to match its own domain.

---

## 1. Package layout

### 1.1 Required structure

`[REQUIRED]` Every clinical feature lives under
`com.medcore.clinical.{feature}.*`. Within that feature, five
sub-packages carry specific responsibilities. Package paths are
read by **ArchUnit Rules 10, 11, 12, 13** — wrong packaging is a
CI failure, not a style note.

| Sub-package | Responsibility | ArchUnit rule that cares |
| ---- | ---- | ---- |
| `api` | HTTP controllers + request/response DTOs | Rule 12 (WriteGate.apply only called from `..api..`) |
| `write` | Commands, validators, policies, handlers, auditors, snapshots, bean-wiring config | Rule 11 (WriteAuditor implementations must live in `..write..`) |
| `service` | `@Component` classes that hold logic callable from handlers | Rule 13 (`@Component` classes must depend on `PhiSessionContext`) |
| `persistence` | JPA entities + Spring Data repositories | Cross-module boundary rules (Rule 10) |
| `model` | Closed enums + value types | No ArchUnit rule (enums have no behaviour) |

**`[INCIDENTAL]` RLS style options (v1.1 addition).** A feature's
tables MAY choose between two RLS styles:

1. **Direct both-GUCs** (4A.2 patient pattern) — every policy
   independently checks `tenant_id = GUC` + membership. Best for
   top-level aggregate roots.
2. **Parent-delegation via EXISTS subquery** (4A.3 identifier
   satellite pattern) — policies key on `EXISTS (SELECT 1 FROM
   <parent> p WHERE p.id = ...)`. The subquery runs under the
   parent's SELECT policy, inheriting tenant scoping + membership.

**`[REQUIRED]` Delegation caveat.** If a satellite's WRITE
policies delegate to the parent via EXISTS, the parent's SELECT
policy must carry the satellite's needed role gate — OR the
satellite's write policies must add an explicit role check in
their subquery. 4A.1 V14 made the first mistake (delegated to
`p_patient_select` which has no role gate); 4A.3 V17 fixed it
by inlining explicit role check in the satellite's subquery.
**Default to explicit role check in satellite write policies
unless the parent policy already has one that matches.**

### 1.2 Incidental specifics from 4A.2

`[INCIDENTAL]` The 4A.2 feature name is `patient` — the feature
name under `clinical.*` matches the resource. Future features
name themselves after their resource (`encounter`, `appointment`,
`medication`).

`[INCIDENTAL]` 4A.2 split `write` further into functional buckets
inside a single package (commands + handlers + policies + etc.
all in `.write.*`). A large feature MAY introduce subdirectories
(`.write.create`, `.write.update`) if file counts justify; the
contract is that all write-stack classes are reachable via
`..write..` for ArchUnit purposes.

`[INCIDENTAL]` 4A.2 placed the `mrn` generator in
`clinical.patient.mrn.*` (a sibling package, not a sub-package of
`service`). That was a judgement call because the generator has
no PHI dependency on its own. Future slices with domain-specific
id generators can follow the same pattern or keep them in
`service` — either is acceptable.

---

## 2. Command — the immutable intent

### 2.1 Contract

`[REQUIRED]` A write operation is represented by a single Kotlin
`data class` named `{Action}{Resource}Command`. The command is
immutable and carries every piece of information the handler
needs to execute. Commands never carry behaviour.

`[REQUIRED]` The command MUST include enough context to identify
the target tenant at the handler layer (typically a `slug: String`
field that the handler resolves to a tenant UUID via
`TenantRepository.findBySlug`).

`[REQUIRED]` Optimistic-concurrency commands MUST carry
`expectedRowVersion: Long`. Missing that field is a design bug.

### 2.2 PHI discipline

`[REQUIRED]` A command type that carries PHI (name, DOB,
demographics, identifiers, etc.) MUST NOT be emitted to log
lines, MDC keys, tracing attributes, or error response bodies.
`PatientLogPhiLeakageTest` enforces this at CI. The handler's
`log.error("…", command)` is a PHI-leak bug.

`[REQUIRED]` Commands use native Kotlin types for their fields
(`String`, `UUID`, `LocalDate`, closed enums). FHIR wire-value
coercion happens in the DTO → command transition, not in the
command.

### 2.3 Partial-update commands

`[REQUIRED]` PATCH-style commands that allow partial updates
MUST wrap each mutable field in a three-state type with
`Absent` / `Clear` / `Set<T>` states. 4A.2 ships the reference
`Patchable<T>` sealed class in
`com.medcore.clinical.patient.write.Patchable`. Future slices
reuse that type or a morally-equivalent one with the same three
states.

`[REQUIRED]` Three-state semantics:
- `Absent` — caller did not mention the field → handler leaves
  the column unchanged.
- `Clear` — caller sent JSON `null` → handler writes NULL. Must
  be refused by the validator for NOT-NULL columns with error
  code `required`.
- `Set<T>` — caller sent a value → handler writes that value.

`[REQUIRED]` A no-op update (every `Set` / `Clear` resolves to
the existing value) returns `changed = false`. The auditor
suppresses emission on no-op. "Every persisted change emits an
audit row" holds because no-ops persist nothing.

`[INCIDENTAL]` The class name `Patchable<T>` itself. Future
slices can rename if they want; the three-state semantic is the
contract.

### 2.4 Reference

`com.medcore.clinical.patient.write.CreatePatientCommand` —
flat command, no partial fields.
`com.medcore.clinical.patient.write.UpdatePatientDemographicsCommand`
— partial command with `Patchable<T>` wrapping + the
`changingFieldNames()` helper the auditor consumes.

---

## 3. Validator — domain checks Bean Validation cannot express

### 3.1 Contract

`[REQUIRED]` Every command type has a `@Component`-scoped
validator implementing `WriteValidator<CMD>`. The validator runs
**outside** the WriteGate's transaction, before authorization.

`[REQUIRED]` Validators throw `WriteValidationException(field,
code)` with:
- `field` — the DTO field name (camelCase). Matches the wire
  shape the caller sees.
- `code` — a short closed-enum slug (`blank`, `format`,
  `too_long`, `control_chars`, `in_future`, `too_old`,
  `required`, `no_fields`, `malformed`, `not_string`,
  `not_date`, `negative`, `wildcard_rejected`). The 3G handler
  emits `details.validationErrors = [{field, code}]`.

`[REQUIRED]` Validators MUST NOT include the rejected *value* in
any thrown exception. Field + code only. Rejected values are
PHI.

`[REQUIRED]` Domain checks that Bean Validation cannot cleanly
express belong here:
- Post-trim emptiness (Bean Validation's `@NotBlank` allows
  whitespace-only in some Jackson modes)
- Control-character rejection (tabs, NEL, NUL — PHI text fields
  never contain these legitimately)
- Length caps with domain rationale (FHIR HumanName parts cap at
  200 chars in 4A.2, for example)
- Date bounds (birth date not in the future; not before 1900)
- Cross-field invariants
- Closed-enum wire-value parsing (e.g., `AdministrativeSex.fromWire`)

### 3.2 Partial-update validators

`[REQUIRED]` A validator for a PATCH command MUST:
- Refuse `Patchable.Clear` on NOT-NULL columns with code
  `required`.
- Validate the value inside `Patchable.Set<T>` — apply the same
  shape checks as the corresponding create validator would.
- Emit code `no_fields` when zero patchable fields are present
  (empty-body PATCH).

### 3.3 Incidental specifics

`[INCIDENTAL]` The specific set of checks a given validator
performs. `CreatePatientValidator`'s list of checks is tailored
to the patient domain; an encounter-create validator will have
its own list.

`[INCIDENTAL]` The specific length caps, date bounds, and
control-char regex. Choose domain-appropriate values.

### 3.4 Reference

`com.medcore.clinical.patient.write.CreatePatientValidator`
`com.medcore.clinical.patient.write.UpdatePatientDemographicsValidator`

---

## 4. Authorization policy

### 4.1 Contract

`[REQUIRED]` Every command type has a `@Component`-scoped policy
implementing `AuthzPolicy<CMD>`. The policy runs **outside** the
WriteGate's transaction, after the validator.

`[REQUIRED]` Policies resolve the caller's authority set via
`AuthorityResolver.resolveFor(userId, tenantSlug)` and throw
`WriteAuthorizationException(WriteDenialReason)` on denial. The
gate catches the throw, routes through `auditor.onDenied`, and
the 3G handler maps to 403 `auth.forbidden`.

`[REQUIRED]` Policy checks belong here ONLY when the answer is
derivable from `(principal, tenant-scoped authorities, command)`
WITHOUT reading target-row state. Target-row-dependent checks
(e.g., "target membership is OWNER → caller needs
`TENANT_DELETE`") belong in the handler (§5.5) because they
require the RLS-GUCd transaction to be open first.

`[REQUIRED]` The 403 response body carries a uniform "Access
denied." message regardless of denial reason. The specific
`WriteDenialReason` is captured only in the audit row, via the
auditor's `onDenied`. Rule 01's "no enumeration signal"
invariant applies.

### 4.2 Incidental specifics

`[INCIDENTAL]` The specific authority the policy checks
(`PATIENT_CREATE`, `PATIENT_UPDATE`, etc.). Each command maps to
a specific `MedcoreAuthority` entry.

`[INCIDENTAL]` Whether the policy performs additional checks
beyond the single authority assertion (e.g., escalation guards
like 3J.N's "promote-to-OWNER requires `TENANT_DELETE`").
Additional checks are domain-specific.

### 4.3 Reference

`com.medcore.clinical.patient.write.CreatePatientPolicy`
`com.medcore.clinical.patient.write.UpdatePatientDemographicsPolicy`

---

## 5. Handler — the transactional worker

### 5.1 Contract

`[REQUIRED]` Every command type has a `@Component`-scoped
handler with signature:
```kotlin
fun handle(command: CMD, context: WriteContext): {Action}{Resource}Snapshot
```

`[REQUIRED]` Handlers MUST NOT be `@Transactional`. The
`WriteGate` owns the transaction; `@Transactional` on the
handler creates nested-transaction ambiguity. ArchUnit does not
explicitly check this today (carry-forward for a future rule) —
follow the convention.

`[REQUIRED]` Handlers are called EXCLUSIVELY from inside a
`WriteGate.apply { ... }` lambda. ArchUnit Rule 12 enforces that
`WriteGate.apply` is only invoked from `..api..` packages, which
transitively constrains handler reach.

`[REQUIRED]` Handler execution order (for create + update):
1. Load the tenant row by slug.
2. Load any target row(s) by id; refuse cross-tenant access with
   `EntityNotFoundException` → 404. **Identical response to
   "does not exist" and "exists in another tenant"** so existence
   does not leak.
3. Apply any target-row-dependent authz checks (throw
   `WriteAuthorizationException` → 403 via gate's catch path).
4. Apply any business invariants (throw `WriteConflictException`
   with a closed-enum code → 409).
5. Apply the mutation.
6. Return a `Snapshot` carrying the post-mutation state, the
   `changed` flag, and any auditor-needed metadata
   (`changedFields`, `priorRole`, etc.).

### 5.2 Optimistic concurrency

`[REQUIRED]` Handlers for PATCH-like commands MUST compare
`command.expectedRowVersion` to the loaded row's `rowVersion`
BEFORE any mutation. Mismatch → `WriteConflictException("stale_row")`
→ 409 with `details.reason = stale_row`. This is defence in
depth over JPA `@Version`: surface the mismatch cleanly with a
specific code before any DB write is attempted.

### 5.3 Cross-tenant id-probe defence

`[REQUIRED]` When a handler loads a target row by id (membership,
patient, etc.), it MUST compare the row's `tenant_id` to the
tenant resolved from the slug. Mismatch → same
`EntityNotFoundException` as "unknown id" to prevent existence
leaks. This rule applies even when RLS would also hide the row —
belt and braces.

### 5.4 Transaction semantics

`[REQUIRED]` Anything a handler throws rolls back the entire
transaction — including the mutation AND the audit emission.
Handlers MUST NOT catch exceptions to swallow them; rethrow or
let them propagate. ADR-003 §2: audit failure fails the audited
action.

### 5.5 Target-row-dependent authz

`[REQUIRED]` If a check depends on reading the target row
(e.g., "only the patient's primary care provider can update
this patient"), it runs in the handler AFTER `PhiRlsTxHook` has
set the RLS GUCs. The handler throws
`WriteAuthorizationException(reason)`; the gate catches this
and routes through `auditor.onDenied`, preserving the uniform
403 response + denial-audit-row contract.

### 5.6 Snapshot

`[REQUIRED]` Handlers return a `data class` named
`{Action}{Resource}Snapshot` (or equivalent). The snapshot is
plain data — no JPA entity references, no lazy loading, no
mutable state. Controllers construct response DTOs directly
from the snapshot.

`[REQUIRED]` Snapshots for CREATE include the full post-create
state. Snapshots for UPDATE include `changed: Boolean` +
`changedFields: Set<String>` (or equivalent) so the auditor can
emit a `fields:<csv>` slug without re-reading the entity.

### 5.7 Incidental specifics

`[INCIDENTAL]` The specific repository call used (JPA
`findById`, JPA named query, JDBC). 4A.2 uses JPA. Future
slices may use JDBC directly if they have a good reason — as
long as RLS still applies.

`[INCIDENTAL]` The internal structure of the Snapshot data
class. Any shape that controllers can consume is fine.

### 5.8 Reference

`com.medcore.clinical.patient.write.CreatePatientHandler`
`com.medcore.clinical.patient.write.UpdatePatientDemographicsHandler`
`com.medcore.clinical.patient.write.PatientSnapshot`

---

## 6. Auditor — audit contract

### 6.1 Contract

`[REQUIRED]` Every command type has a `@Component`-scoped
auditor implementing `WriteAuditor<CMD, R>`. ArchUnit Rule 11
enforces that auditors live under `..write..`.

`[REQUIRED]` Success audit rows follow the NORMATIVE shape:

```
action       = <dedicated AuditAction entry — e.g., PATIENT_CREATED>
actor_type   = USER
actor_id     = context.principal.userId
tenant_id    = result.snapshot.tenantId
resource_type = "<domain>.<resource>"  (closed enum of strings)
resource_id  = <UUID of the affected row, stringified>
outcome      = SUCCESS
reason       = "intent:<domain>.<resource>.<action>[|<metadata_key>:<token>]"
```

`[REQUIRED]` Denial audit rows use the generic
`AuditAction.AUTHZ_WRITE_DENIED`:

```
action       = AUTHZ_WRITE_DENIED
actor_type   = USER
actor_id     = context.principal.userId
tenant_id    = <null or tenant UUID, see §6.2>
resource_type = "<domain>.<resource>"
resource_id  = <null or target UUID, see §6.2>
outcome      = DENIED
reason       = "intent:<domain>.<resource>.<action>|denial:<reason_code>"
```

### 6.2 Resource-id on denial

`[REQUIRED]` On DENIAL, the `resource_id` convention is:
- For CREATE: `null` (the target UUID does not yet exist).
- For UPDATE / PATCH / DELETE on a specific target: the target
  UUID. The URL path variable carries the target id regardless
  of denial, so recording it is sound.

`[REQUIRED]` On DENIAL, `tenant_id` is `null` when the denial
comes from the AuthorityResolver with reason `NOT_A_MEMBER`
(the slug-to-UUID mapping may not have resolved). It is the
tenant UUID when the denial is role-insufficient and a
tenant-membership row exists.

### 6.3 Reason slug discipline

`[REQUIRED]` Reason slugs are pipe-separated key:value tokens.
All tokens are closed-enum or stable identifiers — **never
PHI, never free-form strings**. Reason tokens in 4A.2:

- `intent` — dotted `<domain>.<resource>.<action>` slug
- `mrn_source` — `GENERATED` | `IMPORTED` (closed enum)
- `fields` — comma-separated list of camelCase field names
  (names only, never values). Order matches command declaration.
- `denial` — closed-enum `WriteDenialReason.code`.
- `from` / `to` (3J.N role updates) — closed-enum tokens.

`[REQUIRED]` Adding a new reason-slug key REQUIRES a new token
in this catalog (via an ADR amendment or superseding design
pack). A slice that invents a new free-form token drifts the
contract.

### 6.4 No-op suppression

`[REQUIRED]` Auditors emit nothing when the snapshot indicates
no change was persisted. `"Every persisted change emits an
audit row"` holds because no-ops persist no change. The
pattern:

```kotlin
override fun onSuccess(command: CMD, result: R, context: WriteContext) {
    if (!result.changed) return
    auditWriter.write(...)
}
```

### 6.5 Atomicity

`[REQUIRED]` `onSuccess` runs INSIDE the gate's transaction.
Failure rolls back the audited mutation (ADR-003 §2). The
`JdbcAuditWriter` participates via `PROPAGATION_REQUIRED`.

`[REQUIRED]` `onDenied` runs OUTSIDE the gate's transaction
(the gate never opened one on the denial path). `JdbcAuditWriter`
opens its own short-lived tx via `PROPAGATION_REQUIRED`.

### 6.6 AuditAction registry

`[REQUIRED]` Every new successful audit action gets a dedicated
`AuditAction` enum entry with a wire-stable `code` string
(`clinical.patient.created`, `clinical.patient.demographics_updated`,
etc.). The enum's KDoc includes the NORMATIVE shape contract
for that action (actor_type, tenant_id, resource_type,
resource_id, reason template).

`[REQUIRED]` Renaming or removing a shipped action is a
breaking wire-contract change. Superseding ADR required.

### 6.7 Reference

`com.medcore.clinical.patient.write.CreatePatientAuditor`
`com.medcore.clinical.patient.write.UpdatePatientDemographicsAuditor`
`com.medcore.platform.audit.AuditAction` (registry)

---

## 7. HTTP surface — controller + DTOs + bean wiring

### 7.1 Controller

`[REQUIRED]` Controllers live in `..api..`. ArchUnit Rule 12
enforces.

`[REQUIRED]` Controllers construct commands from Bean-validated
DTOs + path variables + headers, build a `WriteContext`, and
call `writeGate.apply(command, context) { cmd ->
handler.handle(cmd, context) }`. Controllers do NOT call
handlers directly.

`[REQUIRED]` Controllers emit audit nothing directly. All audit
emission goes through the `WriteAuditor` bound to the gate.

`[REQUIRED]` Successful POST returns 201 + the created resource
body. Successful PATCH returns 200 + the updated resource body.

`[REQUIRED]` Every mutating response includes:
- `ETag: "<rowVersion>"` header (RFC 7232 strong ETag form).
- `rowVersion: <long>` field in the response body.

Both mechanisms carry the same value so clients can use either
`If-Match` or a programmatic round-trip on the next mutation.

`[REQUIRED]` Response body envelope: `WriteResponse<T>` with
fields `data` (the typed payload) + `requestId` (from
`MDC.get(MdcKeys.REQUEST_ID)`).

### 7.2 If-Match enforcement (PHI PATCH)

`[REQUIRED]` PATCH endpoints on PHI-bearing resources MUST
require `If-Match`. Missing → throw
`PreconditionRequiredException(headerName = "If-Match")` → 428
`request.precondition_required`. Stale (handler comparison
fails) → `WriteConflictException("stale_row")` → 409.
Wildcard `If-Match: *` → `WriteValidationException(field =
"If-Match", code = "wildcard_rejected")` → 422. No implicit
acceptance of wildcard — blind overwrites of PHI are disallowed.

`[INCIDENTAL]` Whether non-PHI PATCH endpoints also require
`If-Match`. 4A.2's design bar is "PHI = required; non-PHI =
up to each feature." 3J.2's tenant display-name PATCH does not
require it.

**`[REQUIRED]` Scope clarification (v1.1 addition):** the
`If-Match` rule applies to **PHI PATCH** endpoints specifically.
It does NOT apply to:

- **PHI DELETE** (soft-delete / revoke) — these are idempotent
  lifecycle transitions. The second DELETE on an already-
  revoked target returns the same outcome; `If-Match` adds no
  safety. 4A.3 identifier revoke does not require `If-Match`.
- **PHI POST** (create) — the resource doesn't yet exist, so
  there is no row-version to match against.

**Rule of thumb:** `If-Match` is required when the write
transitions mutable state on an existing PHI row AND the
client's desired mutation depends on state the server might
have changed between the client's read and write. Revoke
doesn't meet the second test (revoke-then-revoke is a no-op
regardless of intervening state).

### 7.3 DTO discipline

`[REQUIRED]` Request DTOs are bound with `@Valid @RequestBody`
for POST-like shapes (full body replacement). Bean Validation
(`@NotBlank`, `@NotNull`, `@Size`, etc.) handles
annotation-expressible checks; the domain validator handles
the rest.

`[REQUIRED]` For partial-update PATCH endpoints, the body is
bound as `JsonNode` (not a typed DTO). A `*RequestMapper` walks
the node and builds the command with `Patchable<T>` values for
each known field. Fields outside the mapper's allowlist are
silently dropped — callers cannot smuggle updates to fields
the mapper doesn't recognise.

`[REQUIRED]` Response DTOs are plain `data class`es (NOT JPA
entities). `@JsonInclude(JsonInclude.Include.NON_NULL)` omits
nullable-unset fields from the wire.

`[REQUIRED]` Response DTOs carry a companion
`Companion.from(snapshot: Snapshot): Response` factory.
Controllers call `Response.from(snapshot)` — no direct
field-copying, no reflection.

### 7.4 Bean wiring

`[REQUIRED]` Every feature module has a `@Configuration(proxyBeanMethods
= false)` class (`{Feature}WriteConfig`) that declares one
`@Bean` per command type. Each bean returns
`WriteGate<CMD, R>(policy, auditor, txManager, validator,
txHook)`.

`[REQUIRED]` PHI-bearing gates wire `PhiRlsTxHook` (NOT
`TenancyRlsTxHook`). The V14+ RLS policies on PHI tables key
on BOTH GUCs — `PhiRlsTxHook` sets both; `TenancyRlsTxHook`
sets only `app.current_user_id` and produces zero-row reads +
WITH CHECK failures on PHI tables.

### 7.5 Headers

`[REQUIRED]` `X-Medcore-Tenant: {slug}` required on every
PHI-bearing write request. `TenantContextFilter` resolves it
and populates `TenantContext`; `PhiRequestContextFilter`
promotes it into `PhiRequestContextHolder`. Missing tenant or
membership → 403 `tenancy.forbidden` at filter time with
`tenancy.membership.denied` audit row. Neither the policy nor
the handler runs.

`[REQUIRED]` `Idempotency-Key` header remains SHAPE-ONLY in
4A.2 — accepted, placed on `WriteContext.idempotencyKey`, NOT
yet deduped by the framework. Full dedupe is a future slice.

`[REQUIRED]` `X-Confirm-Duplicate: true` — the duplicate-warning
bypass header on patient create. Similar bypass headers on
future slices follow the same `X-Confirm-<Concern>` pattern
and the same single-purpose semantics (no free-form reason
strings).

### 7.6 Incidental specifics

`[INCIDENTAL]` The exact URL path shape. 4A.2 uses
`/api/v1/tenants/{slug}/patients`. Future features pick paths
that match their domain; the tenant slug in the path remains
consistent with 3B.1's convention.

`[INCIDENTAL]` The exact Jackson annotation strategy (which
fields get `@JsonInclude`, whether `@JsonAlias` is used, etc.).
Choose sensibly per domain.

### 7.7 Reference

`com.medcore.clinical.patient.api.PatientController`
`com.medcore.clinical.patient.api.PatientDtos` (requests +
responses + mapper)
`com.medcore.clinical.patient.write.PatientWriteConfig`

---

## 8. Tests required per feature

### 8.1 Minimum test suite

`[REQUIRED]` Every clinical write feature SHIPS with AT LEAST
the following suites:

1. **Validator unit tests** — one per validator, covering every
   closed-enum `code` the validator can emit.
2. **Policy unit or integration tests** — every authority +
   denial path.
3. **Integration test for every endpoint** — happy path + role
   matrix + cross-tenant refusal + audit-row assertion + ETag
   assertion + validation paths.
4. **Concurrency test** if the feature has any DB-level
   serialisation concern (MRN generation, inventory allocation,
   appointment booking, etc.). Minimum 50 parallel requests.
5. **Rollback test** if the feature has any side-effect that
   must roll back with the transaction (MRN counter, sequence,
   any cross-table state).
6. **PHI log-leakage test** — extends `LogPhiLeakageTest`'s
   pattern with the feature's PHI fields. Runs one POST + one
   PATCH (or analogous) with synthetic distinctive tokens and
   grep-asserts the capture contains none of them.

### 8.2 Incidental specifics

`[INCIDENTAL]` The exact number of test cases per suite. 4A.2's
`CreatePatientIntegrationTest` has 11 cases because the patient
domain has that much variation. An encounter-create test might
have fewer.

`[INCIDENTAL]` The choice of HTTP client. 4A.2 uses
`TestRestTemplate` (Spring Boot). Future slices can use
WebTestClient if they prefer.

### 8.3 Reference

`com.medcore.clinical.patient.mrn.MrnGeneratorTest` (rollback +
concurrency-adjacent)
`com.medcore.clinical.patient.api.CreatePatientIntegrationTest`
`com.medcore.clinical.patient.api.UpdatePatientDemographicsIntegrationTest`
`com.medcore.clinical.patient.api.DuplicatePatientWarningTest`
`com.medcore.clinical.patient.api.PatientCreateConcurrencyTest`
`com.medcore.clinical.patient.api.PatientLogPhiLeakageTest`

---

## 9. File map — 4A.2 reference implementation

```
com.medcore.clinical.patient
├── api/
│   ├── PatientController.kt              # §7.1 controller
│   └── PatientDtos.kt                    # §7.3 DTOs + UpdatePatientDemographicsRequestMapper
├── model/                                # §1.1 closed enums
│   ├── AdministrativeSex.kt
│   ├── MrnSource.kt
│   ├── PatientIdentifierType.kt
│   └── PatientStatus.kt
├── mrn/                                  # domain-specific service sibling
│   ├── MrnFormatKind.kt                  # §1.2 INCIDENTAL — extensible format enum
│   └── MrnGenerator.kt
├── persistence/                          # JPA layer
│   ├── PatientEntity.kt
│   ├── PatientIdentifierEntity.kt
│   ├── PatientIdentifierRepository.kt
│   └── PatientRepository.kt
├── service/                              # §1.1 — @Component classes depending on PhiSessionContext
│   ├── DuplicatePatientDetector.kt       # Rule 13 canonical consumer
│   └── DuplicatePatientWarningException.kt
└── write/                                # §1.1 — Command/Validator/Policy/Handler/Auditor/Config
    ├── CreatePatientAuditor.kt           # §6
    ├── CreatePatientCommand.kt           # §2
    ├── CreatePatientHandler.kt           # §5
    ├── CreatePatientPolicy.kt            # §4
    ├── CreatePatientValidator.kt         # §3
    ├── Patchable.kt                      # §2.3 three-state helper
    ├── PatientSnapshot.kt                # §5.6
    ├── PatientWriteConfig.kt             # §7.4 bean wiring
    ├── UpdatePatientDemographicsAuditor.kt
    ├── UpdatePatientDemographicsCommand.kt
    ├── UpdatePatientDemographicsHandler.kt
    ├── UpdatePatientDemographicsPolicy.kt
    └── UpdatePatientDemographicsValidator.kt
```

Tests:

```
com.medcore.clinical.patient
├── api/
│   ├── CreatePatientIntegrationTest.kt
│   ├── DuplicatePatientWarningTest.kt
│   ├── PatientCreateConcurrencyTest.kt    # §8.1 #4 — 50 parallel
│   ├── PatientLogPhiLeakageTest.kt        # §8.1 #6 — PHI leakage
│   └── UpdatePatientDemographicsIntegrationTest.kt
├── mrn/
│   └── MrnGeneratorTest.kt                # §8.1 #5 — rollback safety
├── PatientEntityMappingTest.kt            # 4A.1 — JPA round-trip
└── PatientSchemaRlsTest.kt                # 4A.1 — RLS contract
```

---

## 10. Starting-a-new-feature checklist

Copy this checklist into your slice-planning doc (or the DoD
entry). Every REQUIRED item MUST be ticked before the slice
ships.

### Schema & migrations
- [ ] `[REQUIRED]` New migration V{N} creates the feature's
  tables with RLS policies that key on BOTH GUCs (for PHI) or
  user GUC + membership check (for tenancy-scope).
- [ ] `[REQUIRED]` `FlywayMigrationStateCheck.MIN_EXPECTED_INSTALLED_RANK`
  bumped to N.
- [ ] `[REQUIRED]` `MedcoreApiApplicationTests` expected-migrations
  list appended with V{N}.

### Command stack (per command type)
- [ ] `[REQUIRED]` Command data class in `..{feature}.write..`.
- [ ] `[REQUIRED]` Validator `@Component` implementing
  `WriteValidator<CMD>`.
- [ ] `[REQUIRED]` Policy `@Component` implementing `AuthzPolicy<CMD>`.
- [ ] `[REQUIRED]` Handler `@Component` (NOT `@Transactional`).
- [ ] `[REQUIRED]` Auditor `@Component` implementing
  `WriteAuditor<CMD, R>`.
- [ ] `[REQUIRED]` Snapshot data class.
- [ ] `[REQUIRED]` Bean wired in `{Feature}WriteConfig.@Bean` —
  PHI-bearing features MUST wire `PhiRlsTxHook`.

### Authority + audit registry
- [ ] `[REQUIRED]` **Decide: does this feature need a new
  `MedcoreAuthority`, or does an existing one fit?**
  (v1.1 addition — force an explicit decision rather than
  letting authority sprawl happen silently.) Reuse is the
  default unless a concrete clinical-role-differentiation
  consumer drives a split. Log the decision in the feature's
  PHI review. 4A.3 chose reuse (`PATIENT_UPDATE` covers
  identifier management).
- [ ] `[REQUIRED]` Any new `MedcoreAuthority` entries landed
  (closed enum, wire-stable strings) + `MembershipRoleAuthorities`
  map updated + `MembershipRoleAuthoritiesTest` updated.
- [ ] `[REQUIRED]` Any new `AuditAction` entries landed with
  NORMATIVE shape-contract KDoc + emission test.

### HTTP surface
- [ ] `[REQUIRED]` Controller in `..{feature}.api..`.
- [ ] `[REQUIRED]` Request + response DTOs.
- [ ] `[REQUIRED]` `X-Medcore-Tenant` header required on PHI routes.
- [ ] `[REQUIRED]` PATCH on PHI: `If-Match` required (428 if
  missing, 409 if stale, 422 if wildcard).
- [ ] `[REQUIRED]` ETag header + rowVersion in response body.
- [ ] `[REQUIRED]` `Idempotency-Key` accepted shape-only.

### Error envelope
- [ ] `[REQUIRED]` Any new error codes added to `ErrorCodes`
  + `@ExceptionHandler` in `GlobalExceptionHandler` or a
  module adviser + integration-test coverage.

### Tests (minimum)
- [ ] `[REQUIRED]` Validator tests.
- [ ] `[REQUIRED]` Policy tests.
- [ ] `[REQUIRED]` Integration tests (happy + role matrix +
  cross-tenant refusal + audit-row + ETag).
- [ ] `[REQUIRED]` Concurrency test if any DB serialisation
  concern (min 50 parallel).
- [ ] `[REQUIRED]` Rollback test if any side-effect must
  roll back.
- [ ] `[REQUIRED]` PHI log-leakage test.

### Test hygiene
- [ ] `[REQUIRED]` Every existing test's `@BeforeEach` reset
  that deletes from `tenancy.tenant` is updated to delete from
  the feature's new tables first (FK-dependency-order cleanup).

### Governance
- [ ] `[REQUIRED]` DoD §3.x.y checklist populated.
- [ ] `[REQUIRED]` Roadmap ledger footer refreshed; new carry-
  forwards opened and existing ones closed as applicable.
- [ ] `[REQUIRED]` PHI-exposure review doc at
  `docs/security/phi-exposure-review-{phase}.md` with risk
  determination + attack-surface analysis.

### Architecture discipline
- [ ] `[REQUIRED]` ArchUnit Rule 12 passes (no WriteGate.apply
  outside `..api..`).
- [ ] `[REQUIRED]` ArchUnit Rule 13 passes (`@Component` classes
  in `..clinical..service..` depend on `PhiSessionContext`).
- [ ] `[REQUIRED]` All other existing architecture rules pass
  (Rules 1–11).

### Final gate
- [ ] `[REQUIRED]` Full test suite green. No failing suites, no
  skipped suites without explicit annotation + rationale.
- [ ] `[REQUIRED]` Single atomic commit (governance + code +
  tests + docs together).

---

## 11. Versioning

This document is **v1.0** as of 2026-04-23 (Phase 4A.2 ship).

Amendments to this pattern happen via **explicit ADR** only. A
slice that discovers a better pattern cannot silently upgrade
this doc — the discovery lands as an ADR amendment first, then
the doc is revised to match, then the slice lands.

The v1.0 pattern was extracted from 4A.2 as-shipped. Any
inconsistency between this document and
`com.medcore.clinical.patient.*` code should be reported — the
code is the canonical reference at v1.0.

### Change log

| Version | Date | Change | Driven by |
| ---- | ---- | ---- | ---- |
| 1.0 | 2026-04-23 | Initial extraction from 4A.2 | Phase 4A.2 stabilization |
| 1.1 | 2026-04-23 | Three non-breaking additions: (a) §10 checklist prompt "new authority or reuse?"; (b) §7.2 clarification — `If-Match` scope is PHI PATCH only (not DELETE or POST); (c) §1.1 RLS delegation option documented + caveat on satellite role gating | Phase 4A.3 pattern-validation |
