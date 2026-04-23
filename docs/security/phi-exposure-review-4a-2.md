# PHI Exposure Review — Phase 4A.2 (patient create + update)

**Slice:** Phase 4A.2 — Medcore's **first real PHI write path**.
Ships `POST /api/v1/tenants/{slug}/patients` and
`PATCH /api/v1/tenants/{slug}/patients/{patientId}` through the
Phase 3J.1 WriteGate perimeter using the Phase 4A.0
`PhiRlsTxHook` for both-GUCs RLS. Adds V15
(`clinical.patient_mrn_counter`) for atomic, tenant-scoped, roll-
back-safe MRN minting, and V16 (moves `fuzzystrmatch` to the
`public` schema so `medcore_app` can call `public.soundex(...)`
for phonetic duplicate detection). Introduces
`DuplicatePatientDetector` (exact + phonetic match via V14's
indexes) with the `X-Confirm-Duplicate: true` bypass header,
`PreconditionRequiredException` (428 for missing `If-Match`),
three-state `Patchable<T>` partial-update semantics, new
`AuditAction` entries (`PATIENT_CREATED`,
`PATIENT_DEMOGRAPHICS_UPDATED`), new error codes
(`clinical.patient.duplicate_warning`, `request.precondition_required`),
and activates **ArchUnit Rule 13** (the `.allowEmptyShould(true)`
allowance placed in 4A.0 is removed — `DuplicatePatientDetector`
is the first `..clinical..service..` class with a real
`PhiSessionContext` dependency).

**Reviewer:** Repository owner (solo).
**Date:** 2026-04-23.
**Scope:** All Phase 4A.2 code + V15 + V16 migrations + tests +
governance docs. ~20 Kotlin files, ~40 new integration tests,
three new governance artifacts (this review, the DoD
checklist, the roadmap ledger touch).

**Risk determination:** **Low.** First slice to expose PHI on an
HTTP surface — the risk is inherently higher than 4A.0/4A.1
(which were substrate + dormant schema). What keeps this at
"Low" rather than "Medium":

- **Defence-in-depth stack is fully land-before-reach:** three
  layers independently enforce tenant + caller scope (Spring
  Security + TenantContextFilter → CreatePatientPolicy /
  UpdatePatientDemographicsPolicy → V14 RLS WITH CHECK +
  role gate). Any one layer failing closed is enough; all
  three failing closed is defence-in-depth.
- **No PHI in logs / traces / error payloads:**
  `PatientLogPhiLeakageTest` asserts the invariant at CI time.
- **Authority gating is narrow:** `PATIENT_CREATE` /
  `PATIENT_UPDATE` are OWNER/ADMIN only — MEMBER cannot write
  (structurally, by the 4A.1 role map). Test matrix proves
  every refusal path.
- **No clinical-role differentiation yet:** a deliberate
  4A.2-scope decision; OWNER/ADMIN access is currently
  broad. Narrower clinical-role access (CLINICIAN / NURSE /
  STAFF) is a future slice's review responsibility.

---

## 1. What this slice handles

### 1.1 PHI fields touched on every request

Both `POST` and `PATCH` accept and persist:

| Field | FHIR / US Core mapping | PHI per 45 CFR §164.514(b) |
| ---- | ---- | ---- |
| `nameGiven` / `nameFamily` / `nameMiddle` / `nameSuffix` / `namePrefix` | `Patient.name[use='official']` | **Yes** — name |
| `preferredName` | `Patient.name[use='usual']` | **Yes** — name |
| `birthDate` | `Patient.birthDate` | **Yes** — DOB |
| `administrativeSex` | `Patient.gender` | Gender identity |
| `sexAssignedAtBirth` | US Core SAB extension | Demographic |
| `genderIdentityCode` | US Core gender-identity extension | Demographic |
| `preferredLanguage` | `Patient.communication.language` | Demographic |

### 1.2 Non-PHI fields

- `slug` — tenant slug (caller-facing identifier, not PHI)
- `mrn` — tenant-scoped opaque identifier, minted server-side
- `rowVersion` — concurrency token
- `id`, `tenantId` — UUIDs
- `createdBy`, `updatedBy` — user UUIDs

### 1.3 What this slice deliberately does NOT ship

- `GET /api/v1/tenants/{slug}/patients/{patientId}` — the read
  endpoint bundles with **4A.5 read-auditing** (the
  `CLINICAL_PATIENT_ACCESSED` action + audit emission from the
  read path). Shipping a GET in 4A.2 without the audit envelope
  would be a compliance gap.
- FHIR-shape `Patient` read (4A.4).
- Patient identifier management (separate 4A.2.1 or 4A.3 slice).
- IMPORTED MRN path (4A.2 ships GENERATED only).
- Real `Idempotency-Key` dedupe persistence — remains
  shape-only, same status as 3J.2/3J.3/3J.N. Full dedupe is
  either a 4A.2.1 hardening slice or rolls up with 6A's
  Stripe-webhook flow.
- Address / contact history tables (4A.3).
- Patient merge workflow (4A.N).

---

## 2. Data-flow review

### 2.1 HTTP entry — controller binding

**POST** `/api/v1/tenants/{slug}/patients`
- `@AuthenticationPrincipal MedcorePrincipal` — Spring Security's
  JWT pipeline validates the bearer and materialises the
  principal. Unauthenticated requests short-circuit to 401 via
  `MedcoreAccessDeniedHandler` / `AuthenticationEntryPoint`.
- `@Valid @RequestBody CreatePatientRequest` — Bean Validation
  runs before the controller body. `@NotBlank` on `nameGiven`
  / `nameFamily` catches null/empty at the MVC boundary (→ 422
  `request.validation_failed`).
- `X-Confirm-Duplicate` header — boolean, defaults to `false`.
  Never logged; never audited; controls a branch in the handler
  only.
- `X-Medcore-Tenant` header — required by `TenantContextFilter`
  to resolve the tenant context. Missing or mismatched membership
  → filter-level 403 `tenancy.forbidden` with a
  `tenancy.membership.denied` audit row. The request never
  reaches the policy layer.
- `Idempotency-Key` — shape-only passthrough; NOT deduped in
  4A.2.

**PATCH** `/api/v1/tenants/{slug}/patients/{patientId}`
- Same principal / tenant / idempotency handling as POST.
- `@RequestBody JsonNode?` — body bound as generic JSON rather
  than a typed DTO. `UpdatePatientDemographicsRequestMapper`
  walks the node for each known patchable field, building
  `Patchable.Absent` / `Patchable.Clear` / `Patchable.Set<T>`.
  Fields NOT in the mapper's allowlist are silently dropped —
  callers cannot smuggle updates to `mrn`, `status`, or other
  immutable columns via the PATCH body.
- `If-Match` — REQUIRED. Missing → 428
  `request.precondition_required`. Stale → 409
  `resource.conflict` with `details.reason = stale_row`. `*`
  wildcard is rejected (422 `wildcard_rejected`) so clients
  cannot blind-overwrite.

### 2.2 Pre-transaction — validator + policy

- **Validator** (`CreatePatientValidator` /
  `UpdatePatientDemographicsValidator`) runs outside the tx.
  Checks: slug format, post-trim emptiness, control-char
  rejection, length caps, date bounds (not future, not before
  1900), closed-enum wire values for `administrativeSex`.
  Throws `WriteValidationException(field, code)` → 422.
  **Field values NEVER leak** — only `{field, code}` slugs.
- **Policy** (`CreatePatientPolicy` /
  `UpdatePatientDemographicsPolicy`) runs outside the tx.
  Resolves `AuthorityResolution` via `AuthorityResolver` and
  asserts the caller holds `PATIENT_CREATE` /
  `PATIENT_UPDATE`. Throws `WriteAuthorizationException(reason)`
  → 403 `auth.forbidden` + denial audit row via the auditor's
  `onDenied` path.

### 2.3 Transaction open — `PhiRlsTxHook`

Before the handler's lambda runs, `WriteGate` fires
`PhiRlsTxHook.beforeExecute(context)`. The hook calls
`PhiSessionContext.applyFromRequest()` which reads the
`PhiRequestContextHolder` and sets both RLS GUCs via
`SET LOCAL`. If the holder is empty (e.g., a scheduled-job
path bypassed the filter chain),
`PhiContextMissingException` fires → 500 `server.error`.
**Loud failure is deliberate** — silent no-op would risk a
PHI-access path that bypasses RLS.

### 2.4 Handler — create path

`CreatePatientHandler.handle(command, context)`:

1. Load the tenant row by slug (`TenantRepository.findBySlug`).
   RLS-gated — caller holds `PATIENT_CREATE` which requires
   ACTIVE membership, so the row is visible.
2. If `command.confirmDuplicate == false`, call
   `DuplicatePatientDetector.detect(tenantId, birthDate,
   nameFamily, nameGiven)`. If any candidates, throw
   `DuplicatePatientWarningException(candidates)` → 409.
3. Mint MRN via `MrnGenerator.generate(tenantId)` — atomic
   `INSERT ... ON CONFLICT DO UPDATE ... RETURNING`. Runs
   inside the handler's tx; rollback un-consumes the MRN.
4. Build `PatientEntity` with `status = ACTIVE`,
   `mrnSource = GENERATED`, `createdBy = updatedBy =
   caller.userId`.
5. `patientRepository.saveAndFlush(entity)` — RLS WITH CHECK
   validates tenant + ACTIVE-OWNER/ADMIN membership.
6. Return `PatientSnapshot`.

### 2.5 Handler — update path

`UpdatePatientDemographicsHandler.handle(command, context)`:

1. Load tenant by slug.
2. Load patient by id (`PatientRepository.findById`). 404 if
   missing OR if it belongs to a different tenant — same
   response for either, preventing cross-tenant id-probing
   existence leaks.
3. Compare `patient.rowVersion` to `command.expectedRowVersion`.
   Mismatch → `WriteConflictException("stale_row")` → 409
   with `details.reason = stale_row`.
4. Apply every `Patchable` field — `Set` writes value, `Clear`
   writes NULL (validator already refused `Clear` on required
   columns), `Absent` leaves unchanged. No-op detection: only
   fields whose new value differs from current count as
   changes.
5. If any field changed: stamp `updatedAt` + `updatedBy`,
   save. JPA `@Version` bumps `rowVersion` at flush.
6. Return `UpdatePatientDemographicsSnapshot(snapshot, changed,
   changedFields)`. `changedFields` is the closed-enum set
   used by the auditor's `fields:` reason slug.

### 2.6 Auditor — success + denial

**Success (`CreatePatientAuditor.onSuccess`):**
- `action = PATIENT_CREATED` (`clinical.patient.created`)
- `actor_id` = caller userId
- `tenant_id` = target tenant UUID
- `resource_type` = `"clinical.patient"`
- `resource_id` = new patient UUID
- `outcome = SUCCESS`
- `reason = intent:clinical.patient.create|mrn_source:GENERATED`

**Success (`UpdatePatientDemographicsAuditor.onSuccess`):**
- `action = PATIENT_DEMOGRAPHICS_UPDATED`
- `resource_id` = target patient UUID
- `reason = intent:clinical.patient.update_demographics|fields:<csv>`
  where `<csv>` is the camelCase field-name list — no values.
- **Suppressed on no-op** (`snapshot.changed == false`).

**Denial (both auditors' `onDenied`):**
- `action = AUTHZ_WRITE_DENIED`
- `resource_type = "clinical.patient"`
- `resource_id` = patient UUID for PATCH denials (the target
  is known); NULL for POST denials (the patient UUID does
  not yet exist at denial time)
- `reason = intent:clinical.patient.{create|update_demographics}|denial:<code>`

**Emitted inside the same transaction as the handler's
mutation.** ADR-003 §2: audit failure rolls back the
mutation. `JdbcAuditWriter` participates in the existing
tx via `PROPAGATION_REQUIRED`.

### 2.7 Response serialisation

Both endpoints return `WriteResponse<PatientResponse>`:
- `data` — full `PatientResponse` DTO including every PHI
  field (callers that created/updated the row already know
  them — no additional disclosure).
- `requestId` — from `MDC.get(MdcKeys.REQUEST_ID)`.
- `ETag: "<rowVersion>"` header — clients use for next `If-Match`.
- Jackson serialises directly from `PatientResponse`; no
  interstitial stringification.
- `@JsonInclude(NON_NULL)` omits unset nullable columns from
  the wire.

---

## 3. V15 + V16 — migration review

### 3.1 V15 — `clinical.patient_mrn_counter`

- **Shape:** one row per tenant, PK on `tenant_id`, FK to
  `tenancy.tenant`. Columns: `prefix` (TEXT, default `''`),
  `width` (INT, default `6`), `format_kind` (TEXT, default
  `NUMERIC`, CHECK-constrained), `next_value` (BIGINT,
  default `1`, CHECK >= 1), audit columns + `row_version`.
- **RLS** (both-GUCs, parallel to V14's patient policies):
  - SELECT — ACTIVE membership.
  - INSERT / UPDATE — ACTIVE membership + OWNER/ADMIN role.
  - No DELETE grant to `medcore_app` (counters don't get
    removed in normal operation).
- **Concurrency contract** (inline in the migration comment):
  `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` is the
  ONLY supported mutation path. Postgres's uniqueness
  machinery serialises concurrent upserts — no explicit
  `FOR UPDATE` lock.
- **Extensibility** (design-pack refinement #1): columns
  `prefix`, `width`, `format_kind` shaped so future
  `ALPHANUMERIC` / `CHECK_DIGIT_MOD10` formats are additive
  (enum + CHECK update + generator branch), not breaking.
- **Rollback:** acceptable at 4A.2 scale (counter has no
  durable meaning in a fresh env). Forward-only once any
  real clinic mints MRNs.

### 3.2 V16 — fuzzystrmatch schema relocation

- Single statement: `ALTER EXTENSION fuzzystrmatch SET SCHEMA
  public` + `GRANT EXECUTE ON FUNCTION public.soundex(text)
  TO medcore_app`.
- **Why needed:** V14 installed the extension without `WITH
  SCHEMA`; Flyway's `default-schema: flyway` meant the
  functions landed in `flyway`. Runtime `medcore_app` has
  no USAGE on `flyway`, so unqualified `soundex(...)` calls
  would fail at runtime even though V14's index creation
  succeeded under the migrator session.
- **Normative rule** (documented in V16 + `DuplicatePatientDetector`
  KDocs): clinical SQL using fuzzystrmatch functions MUST
  qualify them with `public.` — defence against role-level
  `search_path` drift.
- **Rollback:** forward-only (the 4A.2 detector SQL already
  uses `public.soundex`; reverting would re-break it).

### 3.3 `FlywayMigrationStateCheck.MIN_EXPECTED_INSTALLED_RANK`
bumped 14 → 16. Stale-schema deployments refuse to start.

### 3.4 Threat model (migrations)

- **Extension poisoning (SECURITY DEFINER shadowing):** V16
  moves `fuzzystrmatch` to `public`. `soundex` is a regular
  function (not SECURITY DEFINER), so no ownership escalation
  surface. GRANT EXECUTE is scoped to `medcore_app` only.
- **Counter-row enumeration:** RLS restricts visibility to
  ACTIVE members only. An attacker with no membership cannot
  probe whether a tenant has minted any patients (the counter
  row's existence is hidden).

---

## 4. Log-emission review (NORMATIVE)

No new `Logger` sites added by 4A.2's application code. Existing
sites (RequestIdFilter, MdcUserIdFilter, SQL logging, tracing)
are disciplined by the existing rules (Rule 01, 3F.1
LogPhiLeakageTest, 3F.2 TracingPhiLeakageTest). 4A.2 adds
`PatientLogPhiLeakageTest` as the first clinical-field coverage
layer.

### 4.1 Explicit allow-list (what MAY appear in clinical logs)

- `request_id` — correlation UUID from `RequestIdFilter`.
- `user_id` — caller UUID (already logged via `MdcUserIdFilter`).
- `tenant_id` — target tenant UUID (already logged via tenancy
  filter).
- Patient `id` — tenant-scoped opaque UUID. Not PHI by itself.
- `mrn` — tenant-scoped opaque identifier; not broadly
  PHI-equivalent (no name/DOB can be reconstructed from an
  MRN alone; joining MRN back to PHI requires a RLS-gated
  SELECT).
- Audit action + reason slugs — closed-enum tokens only (no
  values).
- Exception types (e.g., `PhiContextMissingException`,
  `WriteConflictException`) — class names only; message
  content NEVER echoed to the response body, and we ensure
  messages never carry PHI.

### 4.2 Explicit deny-list (what MUST NOT appear in clinical logs)

- Any name part (`nameGiven`, `nameFamily`, `nameMiddle`,
  `nameSuffix`, `namePrefix`, `preferredName`).
- `birthDate` in any form (ISO-8601 string, parsed
  `LocalDate`, Unix epoch).
- `administrativeSex` value (even the wire form).
- `sexAssignedAtBirth`, `genderIdentityCode`,
  `preferredLanguage` values.
- Patient identifier values (from the `clinical.patient_identifier`
  satellite, when 4A.3 ships).
- Request body fragments (even field names accompanied by
  values; the 422 envelope's `{field, code}` shape is the
  only authorised "field name" surface and carries no
  values).

### 4.3 Verification

- `PatientLogPhiLeakageTest` fires a `POST` + `PATCH` with
  synthetic distinctive PHI tokens (random suffixed names,
  a language tag unlikely to appear elsewhere in code) and
  grep-asserts captured stdout does NOT contain any of them.
- Existing `LogPhiLeakageTest` + `TracingPhiLeakageTest`
  continue to pass — 4A.2 adds no new MDC keys + no new
  `medcore.*` tracing attributes.

---

## 5. Attack-surface considerations

### 5.1 MRN collision under concurrency

**Prevented.** `INSERT ... ON CONFLICT DO UPDATE ... RETURNING`
is atomic in Postgres. Two concurrent transactions into the
same tenant serialise via the PK conflict path; the second
reads the value the first committed. Counter is monotonic
per tenant. **Verified by** `PatientCreateConcurrencyTest`
— 50 parallel `POST /patients` into the same tenant yield
50 distinct contiguous MRNs (`000001..000050`), no gaps, no
duplicates.

### 5.2 MRN consumed on failed transaction

**Prevented.** `MrnGenerator.generate()` runs inside the
caller's tx. If the handler's INSERT fails later (CHECK
violation, duplicate-warning throw, audit failure), the tx
rolls back and the counter bump rolls back with it. **Verified
by** `MrnGeneratorTest.rollback in the caller's tx rolls back
the counter bump — MRN is NOT consumed` and
`MrnGeneratorTest.rollback after the first committed mint rolls
back ONLY the second increment`. **Also verified
end-to-end** by `DuplicatePatientWarningTest.duplicate check
does not advance MRN counter — failed create leaves counter
alone`.

### 5.3 Cross-tenant id-probe via PATCH

**Prevented.** Handler's "patient.tenantId != tenant.id →
throw EntityNotFoundException" path returns 404 — identical
response for "patient does not exist" and "patient exists
in a different tenant". No existence leak. **Verified by**
`UpdatePatientDemographicsIntegrationTest.cross-tenant
patientId — 404`.

### 5.4 Privilege escalation via MEMBER direct SQL

**Prevented by** V14 RLS WITH CHECK + V15 role gate. A
MEMBER-role caller reaching the repository (hypothetically,
if a future service bypassed the policy) would be refused
at the RLS layer. **Verified by** `PatientSchemaRlsTest`
(4A.1) + `CreatePatientIntegrationTest.MEMBER cannot create
patient`.

### 5.5 Forgetting `PhiSessionContext.applyFromRequest()` in 4A.3+

**Prevented structurally.** Three independent gates:
1. **ArchUnit Rule 13** — @Component classes in
   `..clinical..service..` MUST depend on `PhiSessionContext`.
   Active as of 4A.2.
2. **Fail-closed RLS** — if GUCs are unset, every clinical
   SELECT returns zero rows. The symptom is "patient
   disappeared" rather than silent PHI access.
3. **`PhiContextMissingException`** — if the
   `PhiRequestContextHolder` is empty when a write gate or a
   service method calls `applyFromRequest()`, loud 500
   failure. Tested by `PhiRlsTxHookTest.beforeExecute without
   holder throws PhiContextMissingException` (4A.0).

### 5.6 Duplicate-warning enumeration (design-pack refinement #2)

**Residual risk.** A hostile OWNER/ADMIN could synthesize
patient payloads against `POST /patients` to enumerate
candidates — receiving `{patientId, mrn}` lists per query.
**Current mitigations:**

- **Authority gate** — only OWNER/ADMIN can reach the detector
  (MEMBER → 403 at the policy layer, never reaches the
  handler). Enumeration requires a compromised privileged
  account already.
- **Minimal-disclosure candidate shape** — only `{patientId,
  mrn}` is echoed back. Not names, not DOB, not demographics.
  An enumeration attack yields UUIDs + MRNs, not identities.
- **Count cap** — `LIMIT 10` per query. Large-scale scraping
  requires many submissions.

**Residual gap** — no rate limiting. **Opens a carry-forward**
(see §9 below) for a future hardening slice.

### 5.7 Partial-update null-wipe of required columns

**Prevented.** `UpdatePatientDemographicsValidator` refuses
`Patchable.Clear` on `nameGiven`, `nameFamily`, `birthDate`,
`administrativeSex` with error code `required` (422). DB-level
NOT-NULL on those columns is a second line of defence.
Verified by `UpdatePatientDemographicsIntegrationTest.PATCH
with null on required column returns 422 required`.

### 5.8 `If-Match` header forgery / stale-race overwrite

**Prevented — two-layer.**

- **Handler comparison:** before any mutation,
  `command.expectedRowVersion != patient.rowVersion` →
  `WriteConflictException("stale_row")` → 409. Clean
  rollback, no DB state touched.
- **JPA `@Version` fallback:** if the handler check somehow
  missed (e.g., future refactor bug), JPA's optimistic-lock
  machinery catches it at flush time →
  `ObjectOptimisticLockingFailureException` → 409 via the
  existing 3G handler. Belt + braces.

**428 for missing `If-Match`** explicitly distinguishes
"you forgot the header" from "you sent a stale version" —
clients can auto-retry the former after refetching, cannot
retry the latter without reconciliation.

### 5.9 Jackson deserialisation gadgets

**Not a concern** for 4A.2's DTO shape — no polymorphic
`@JsonTypeInfo`, no `@JsonSubTypes`, no type-erased
`Object`-typed fields, no `Enable` for `activateDefaultTyping`.
`CreatePatientRequest` is a simple value DTO; the PATCH path
binds to `JsonNode` (Jackson's own AST — no deserialization
of untrusted types).

### 5.10 Payload-size DoS

**Not specifically mitigated in 4A.2.** Spring Boot's default
request-size limits apply (10MB `spring.servlet.multipart.max-request-size`,
no body-length cap on JSON). Realistic patient payloads are
well under 10KB. For 4A.2's "first surface" status,
Spring's defaults are sufficient; a dedicated hardening
slice can tighten this if needed.

### 5.11 Audit log tampering / silent drop

**Prevented by** the append-only audit chain (V7 / V9).
Rule 06 + ADR-003 §2: audit failure fails the audited
action. `WriteAuditor.onSuccess` runs in the gate's
transaction — failure rolls back the mutation. Denial
emission runs in `JdbcAuditWriter`'s own `PROPAGATION_REQUIRED`
tx; if that fails, the denial audit line logs at ERROR
(operator-visible) but the denial itself still propagates
(caller still gets 403). No path silently drops an audit
row.

### 5.12 Tenant suspension mid-flight

**Handled by RLS.** If a tenant transitions from ACTIVE →
ARCHIVED while a patient-create tx is open, the INSERT's
WITH CHECK re-evaluates inside the tx. Cross-tenant
membership changes that drop the caller's ACTIVE status
have the same effect. Either produces a PG-level policy
refusal → 500 via the 3G fallback — rare, acceptable, no
data leak.

---

## 6. Framework additions (4A.2)

### 6.1 `MrnGenerator` + `MrnFormatKind` (new package:
`com.medcore.clinical.patient.mrn`)

- `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` atomic
  upsert. Runs inside the caller's tx (rollback safety).
- `check(TransactionSynchronizationManager.isActualTransactionActive())`
  at entry — loud failure if the caller forgot to open a tx.
- `MrnFormatKind` enum — closed set with room for
  `ALPHANUMERIC` / `CHECK_DIGIT_MOD10` additive expansion.
- `MrnGenerationException` — loud failure on zero-row
  upsert (RLS refusal or tenant-not-found).

### 6.2 `DuplicatePatientDetector` (first
`..clinical..service..` class)

- Two queries hit V14 indexes (`ix_clinical_patient_tenant_dob_family_given`
  + `ix_clinical_patient_tenant_soundex_family`).
- `phiSessionContext.applyFromRequest()` defensive call at
  entry.
- `DuplicateCandidate` top-level (not nested) data class.
- `DuplicatePatientWarningException` moved to `..service..`
  package initially, but ArchUnit Rule 13 refined to only
  apply to `@Component` classes — so exception + data
  classes in the service package pass cleanly.

### 6.3 `Patchable<T>` + partial-update mapper

- Sealed hierarchy `Absent` / `Clear` / `Set<T>` in
  `com.medcore.clinical.patient.write`.
- `UpdatePatientDemographicsRequestMapper` walks a JsonNode
  body and constructs the command. Fields outside the
  allowlist are silently dropped (no side-effect on `mrn`,
  `status`, etc.).

### 6.4 `PreconditionRequiredException` + 428 handler

- First 428 handler in the error envelope. Distinguishes
  "forgot header" from "stale version" for client retry
  semantics.
- Error code `request.precondition_required`.

### 6.5 `DuplicatePatientWarningException` + 409 handler

- Dedicated 409 path (distinct from `WriteConflictException`)
  because `details.candidates` is an array payload — doesn't
  fit `WriteConflictException`'s `details.reason = <slug>`
  shape.
- Error code `clinical.patient.duplicate_warning`.

### 6.6 `PatientWriteConfig`

- First bean-wiring consumer of `PhiRlsTxHook`. Proves the
  4A.0 substrate composes cleanly with a real handler.

### 6.7 `AuditAction` +2 entries

- `PATIENT_CREATED` (`clinical.patient.created`)
- `PATIENT_DEMOGRAPHICS_UPDATED` (`clinical.patient.demographics_updated`)

Both carry NORMATIVE shape contracts on their auditor KDocs.

### 6.8 ArchUnit Rule 13 ACTIVATED

`.allowEmptyShould(true)` removed. Rule narrowed to
`@Component`-annotated classes in `..clinical..service..`.
`DuplicatePatientDetector` is the first consumer.

---

## 7. Conclusion

Phase 4A.2 is the **first time Medcore handles real PHI writes**
from an HTTP caller through a persistence layer. The substrate
(4A.0 PhiRlsTxHook) + schema (4A.1 clinical.patient + RLS) +
handler (4A.2 WriteGate wiring) compose into defense-in-depth
exactly as designed: three independent layers, each
failing-closed, each with its own test matrix.

The residual concerns are:
- **Duplicate-warning enumeration** — mitigated by authority
  gate + minimal-disclosure payload, residually open;
  carry-forward for rate-limiting.
- **MEMBER role over-privilege on reads** — a 4A.5 concern
  (read-audit lands with GET endpoint).
- **Idempotency-Key dedupe** — deferred to 4A.2.1 / 6A.

The three refinements the reviewer explicitly called out
(MRN extensibility design, 50-parallel concurrency test,
MRN rollback test, 428 for missing If-Match) are all
implemented and tested.

**Risk determination confirmed: Low.** Sign-off granted.
Proceed to Phase 4A.3 (address + contact history) or
4A.5 (read endpoint + read-auditing), per roadmap.

---

## 8. Test-coverage summary

| Test suite | Tests | What it proves |
| ---- | ---- | ---- |
| `MrnGeneratorTest` | 5 | Bootstrap, monotonic, tenant-isolated, rollback (x2 cases) |
| `CreatePatientIntegrationTest` | 11 | HTTP→DB, role matrix, filter/policy denial paths, MRN 000001, audit, ETag, cross-tenant block, validation |
| `UpdatePatientDemographicsIntegrationTest` | 9 | PATCH partial, If-Match 428 / 409, no-op suppression, cross-tenant 404, MEMBER denial, audit fields slug |
| `DuplicatePatientWarningTest` | 5 | Exact match, phonetic match, bypass header, no-match, rollback-safe counter |
| `PatientCreateConcurrencyTest` | 1 | 50 parallel creates yield 50 distinct contiguous MRNs |
| `PatientLogPhiLeakageTest` | 1 | No PHI tokens in captured stdout after POST + PATCH |

**Plus 4A.1 (still green):** PatientSchemaRlsTest (10) +
PatientEntityMappingTest (6) = 16. Plus all prior suites.

**Total after 4A.2:** 390/390 across the full suite.

---

## 9. Carry-forwards opened by 4A.2

| Carry-forward | Reason deferred | Target close |
| ---- | ---- | ---- |
| Rate-limiting on duplicate-warning queries | Authority gate is primary mitigation; abuse not yet observed. | Future hardening slice when traffic-shape signals warrant. |
| IMPORTED MRN path + collision-retry against `uq_clinical_patient_tenant_mrn` | 4A.2 ships GENERATED only; IMPORTED's compliance surface (source-system attribution) needs its own review. | When a pilot clinic with legacy data demands it. |
| Real `Idempotency-Key` dedupe persistence + replay | Shape-only still (same status as 3J.2–3J.N). Dedupe is cross-cutting with its own ADR. | 4A.2.1 hardening OR 6A Stripe-webhook flow. |

## 10. Carry-forwards closed by 4A.2

| Carry-forward | Origin | Close |
| ---- | ---- | ---- |
| ArchUnit Rule 13 `.allowEmptyShould(true)` allowance | 4A.0 | **Closed in 4A.2** — `DuplicatePatientDetector` is the first `@Component`-annotated `..clinical..service..` class with real `PhiSessionContext` dependency. |
| `WriteContext.idempotencyKey` — patient-create is the canonical first consumer | 3J.1 | **Shape-only consumption confirmed** (not full dedupe); remains partially open per row above. |
