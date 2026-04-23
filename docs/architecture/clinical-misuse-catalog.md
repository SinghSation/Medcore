# Clinical Write Pattern — Developer Misuse Catalog

**Purpose:** enumerate the ways a developer could accidentally
break the clinical-write pattern locked in v1.0 (see
`docs/architecture/clinical-write-pattern.md`), identify the
guard that catches each, and name the test that proves the
guard works. Any scenario without an automated guard becomes
a pattern-gap finding — either fixable by a new ArchUnit rule
or tracked as a documented carry-forward.

**Scope:** PHI-bearing write paths. 4A.2 is the reference
implementation. Scenarios apply identically to 4A.3, 4B, 4C
and all future clinical write slices.

**Discipline:** treat every scenario as if an otherwise-
competent developer wrote it without reading the pattern doc.
The question is never "is this malicious?" — it's "does the
system fail closed when they do it?".

---

## 0. Legend

- **SCENARIO** — what a developer might accidentally do.
- **GUARD** — the specific mechanism that catches the mistake.
  Categories: `ArchUnit Rule N`, `Compile-time type check`,
  `RLS policy`, `Runtime exception`, `Existing test`,
  `GAP — no automated guard`.
- **PROVING TEST** — the test that verifies the guard fires
  when the scenario is attempted.
- **SEVERITY** — `HIGH` (PHI leak / privilege escalation),
  `MED` (audit / concurrency integrity), `LOW` (code-review
  nit).

If a GUARD is `GAP`, either:
1. A new ArchUnit rule / test is added in this slice, or
2. The gap is tracked as a documented carry-forward with
   rationale for why a rule isn't yet feasible.

---

## 1. WriteGate bypass scenarios

### 1.1 Direct `patientRepository.save(entity)` from outside a handler

**SCENARIO:** a developer adds a new background job, scheduled
task, or admin endpoint that reaches `PatientRepository`
directly and calls `save(...)` or `deleteById(...)`.

**GUARD:** ArchUnit **Rule 12** —
`MutationBoundaryArchTest.repositories_accessed_only_from_sanctioned_layers`
forbids JPA repository calls outside the sanctioned layers
(`..write..` + specific identity/tenancy pathways). ArchUnit
Rule —
`MutationBoundaryArchTest.jpa_mutation_methods_only_from_write_or_identity`
ensures mutation methods (save, delete, merge) are not called
outside those layers.

**PROVING TEST:** `MutationBoundaryArchTest.repositories_accessed_only_from_sanctioned_layers`
(existing). Adding a direct `patientRepository.save()` call
from, say, a new scheduled job package would break this test.

**SEVERITY:** HIGH. Bypassing WriteGate means no
AuthzPolicy, no PhiRlsTxHook (→ no GUCs set → RLS would
actually block the write, but silently — the job succeeds
with zero-row writes is the failure mode), no audit emission.

### 1.2 Calling `writeGate.apply(...)` from a service

**SCENARIO:** a developer adds a `PatientService` method that
internally calls the WriteGate, then calls that service from a
controller. The temptation is "one less repetitive line in
the controller."

**GUARD:** ArchUnit **Rule 12** —
`write_gate_apply_called_only_from_api` restricts
`WriteGate.apply` invocation to `..api..` (+ `..write..` for
tests). A service in `..service..` calling `writeGate.apply`
fails the rule.

**PROVING TEST:** `MutationBoundaryArchTest.write_gate_apply_called_only_from_api`
(existing). Any misuse breaks it at CI time.

**SEVERITY:** MED. Not a direct PHI leak, but introduces a
second entry point that future auditors would have to govern
separately. The rule's KDoc explains: controllers own request
shaping (Bean Validation, principal resolution, MDC, response
envelope) — a service-backed entry point would bypass some of
this.

### 1.3 Handler annotated with `@Transactional`

**SCENARIO:** a developer annotates `CreatePatientHandler`
with `@Transactional` "for safety", creating a nested-tx
situation with WriteGate's tx.

**GUARD:** `GAP — no automated guard.`

**SEVERITY:** MED. Could cause subtle propagation issues
under specific nesting configurations. In practice Spring's
default `PROPAGATION_REQUIRED` would reuse the outer tx so
most cases work silently — which is precisely why this is
hard to catch without a specific rule.

**MITIGATION TODAY:** convention documented in
`clinical-write-pattern.md` §5.1. Code review is the current
guard.

**PROPOSED:** a future ArchUnit rule — "no class in
`..clinical..write..` annotated with `@Transactional`." Added
to the **Misuse-catalog carry-forward ledger (§10, item G-1)**.

---

## 2. PhiSessionContext / RLS scenarios

### 2.1 Clinical service missing `PhiSessionContext` dependency

**SCENARIO:** a developer adds a new `@Component` in
`com.medcore.clinical.{feature}.service` (e.g., a
`PatientSearchIndexer`) and forgets to inject
`PhiSessionContext`.

**GUARD:** ArchUnit **Rule 13** —
`ClinicalDisciplineArchTest.clinical_service_classes_depend_on_phi_session_context`
requires every `@Component` in `..clinical..service..` to
depend on `PhiSessionContext`.

**PROVING TEST:** Rule 13 itself. The rule was activated in
4A.2; `DuplicatePatientDetector` is the canonical first
consumer.

**SEVERITY:** HIGH. A service that runs under WriteGate
today would be fine (`PhiRlsTxHook` sets GUCs), but a future
service called OUTSIDE WriteGate (scheduled job, async
dispatch) would run with unset GUCs → silent zero-row reads.
The compile-time injection dependency is a forcing function
for the developer to realise they need to call
`applyFromRequest()`.

### 2.2 PHI gate wired with `TenancyRlsTxHook` instead of `PhiRlsTxHook`

**SCENARIO:** a developer adds a new patient-write gate in
`PatientWriteConfig.@Bean` and copy-pastes from
`TenantWriteConfig`, ending up with `TenancyRlsTxHook` as the
`txHook`.

**GUARD:** Runtime — V14 RLS policies on `clinical.patient`
key on BOTH `app.current_tenant_id` AND `app.current_user_id`.
`TenancyRlsTxHook` sets ONLY `app.current_user_id`. The
resulting write fails at WITH CHECK with a PG error → 500 via
3G's `onUncaught`.

**PROVING TEST:** `PatientSchemaRlsTest.missing tenant GUC
returns zero patient rows even with user GUC set` (4A.1) —
proves the policy requires both GUCs. Any gate wiring that
produces this state fails at runtime. A new integration test
specifically for gate-misconfiguration is redundant given
the policy-level coverage.

**SEVERITY:** MED. Loud failure (500) on every attempted
write. Developer notices immediately. Not a silent leak.

### 2.3 Scheduled job or async dispatch bypasses filter chain

**SCENARIO:** a future slice introduces a `@Scheduled` job
that reaches `CreatePatientHandler` to auto-create patients
from an external feed. Async path; no HTTP filter runs, so
`PhiRequestContextHolder` is empty.

**GUARD:**
- `PhiRlsTxHook.beforeExecute` calls
  `phiSessionContext.applyFromRequest()`, which throws
  `PhiContextMissingException` when the holder is empty.
- `DuplicatePatientDetector.detect()` also calls
  `applyFromRequest()` as a defensive reinforcement.

**PROVING TEST:** `PhiRlsTxHookTest.beforeExecute without
holder throws PhiContextMissingException` (4A.0).

**SEVERITY:** HIGH (if missed). The throw maps to 500 via 3G
`onUncaught`. Loud failure. No silent zero-row writes.

**FUTURE CARRY-FORWARD (C-5):** 4A.0's `PhiContextPropagator`
async helper pattern is reserved for when a real async PHI
path lands. The 4A.2 pattern template §7.5 restates this as
a known future need.

---

## 3. Logging / PHI leak scenarios

### 3.1 `log.info(command)` directly

**SCENARIO:** a developer debugging a handler writes
`log.info("creating patient: $command")`. Kotlin's string
interpolation calls `toString()` — which on a `data class`
emits every field value including PHI.

**GUARD:**
- Convention: `clinical-write-pattern.md` §2.2 explicitly
  forbids this.
- Integration test: `PatientLogPhiLeakageTest` (4A.2) fires
  real POST + PATCH with distinctive PHI tokens (`Zyxwvut-Given-<uuid>`,
  `Qponmlk-Family-<uuid>`) and grep-asserts no token appears
  in captured stdout.
- Compile-time: 4A.2 commands are data classes, which DO
  emit fields on toString. **The test is the guard.**

**PROVING TEST:** `PatientLogPhiLeakageTest.application logs
never contain patient PHI on successful create + update`
(4A.2). Adding a `log.info($command)` anywhere on the create
or update path breaks this test.

**SEVERITY:** HIGH. PHI-in-logs is a direct compliance
failure. The test is load-bearing.

### 3.2 Adding an MDC key with patient field

**SCENARIO:** a developer adds `MDC.put("patient_name",
command.nameFamily)` to correlate logs across a request.

**GUARD:** `PatientLogPhiLeakageTest` (4A.2) + the existing
3F.1 `LogPhiLeakageTest`. Both grep captured stdout for PHI
tokens; MDC values appear in structured logs, so they'd hit
the grep.

**PROVING TEST:** same as 3.1.

**SEVERITY:** HIGH.

### 3.3 OpenTelemetry span attribute with patient field

**SCENARIO:** `Span.current().setAttribute("patient.name.family",
command.nameFamily)`.

**GUARD:** 3F.2's `TracingPhiLeakageTest` + the narrower
`medcore.*` allowlist. A future slice adding clinical-scope
span attributes must extend the allowlist in
`TracingConfigIntegrationTest`.

**SEVERITY:** HIGH. (Existing guard.)

### 3.4 Error message carrying a patient field

**SCENARIO:** a developer writes
`throw IllegalStateException("patient ${command.nameGiven} is already deleted")`.

**GUARD:**
- `IllegalStateException` is not a specifically-handled
  exception in 3G — it bubbles to `onUncaught`, which logs
  the full message + stack trace to stdout.
- `PatientLogPhiLeakageTest` would catch the logged name.
- The response body is generic `server.error`, so the HTTP
  caller does not see the PHI. But the LOG line would.

**PROVING TEST:** `PatientLogPhiLeakageTest` (same test,
same scenario — forces an exception path and verifies no
PHI tokens land in the capture).

**SEVERITY:** HIGH (if missed). The test is the guard.

**Mitigation today:** convention — `clinical-write-pattern.md`
§5.4 explicitly states handlers MUST NOT include PHI values
in thrown exception messages. Use closed-enum codes in
`WriteConflictException` / `WriteValidationException`.

---

## 4. Authorization / authentication bypass

### 4.1 New controller forgets `@AuthenticationPrincipal`

**SCENARIO:** a developer writes a new patient endpoint
method and forgets the `@AuthenticationPrincipal
MedcorePrincipal principal` parameter, pulling `SecurityContextHolder`
directly or not at all.

**GUARD:**
- Spring Security's `/api/**` filter chain already requires
  authentication. Requests with no valid bearer get 401
  before reaching the controller.
- But a method without `@AuthenticationPrincipal` cannot
  build a `WriteContext(principal = ...)` — `MedcorePrincipal`
  has no no-arg fallback. The endpoint fails to compile OR
  fails at construction time.

**GUARD (secondary):** the `AuthzPolicy.check` inside the gate
calls `authorityResolver.resolveFor(context.principal.userId, ...)`.
A null / missing principal would NPE → 500.

**SEVERITY:** MED. Hard to execute this misuse cleanly —
Spring MVC method-arg resolution produces a compile error or
a 400 before the controller body runs.

**PROVING TEST:** any existing integration test that asserts
401 on unauthenticated requests (e.g.,
`CreatePatientIntegrationTest.POST without bearer token returns 401`).

### 4.2 Controller skips the `X-Medcore-Tenant` header

**SCENARIO:** a developer copy-pastes a controller without
realizing PHI routes need the tenant header resolution.

**GUARD:**
- `PhiRequestContextFilter` does not populate the holder
  without a resolved tenant context.
- `PhiRlsTxHook.beforeExecute` calls `applyFromRequest()`
  → `PhiContextMissingException` → 500.

**PROVING TEST:** no dedicated test today — covered
indirectly by `PhiRlsTxHookTest.beforeExecute without holder
throws PhiContextMissingException`.

**SEVERITY:** MED. Loud failure (500). Developer notices.

### 4.3 Endpoint with `@PreAuthorize("permitAll()")`

**SCENARIO:** a developer adds `@PreAuthorize("permitAll()")`
to a new patient endpoint "while testing".

**GUARD:** ArchUnit **Rule 04** (`SecurityDisciplineArchTest`)
— restricts `permitAll()` paths to an enumerated allowlist
(`/health`, `/info`, `/readiness`, `/liveness`). A new
`@PreAuthorize("permitAll()")` on an `/api/**` endpoint fails
the rule.

**PROVING TEST:** the corresponding ArchUnit rule in
`SecurityDisciplineArchTest`.

**SEVERITY:** HIGH. This would make a PHI endpoint public.

---

## 5. Audit-discipline bypass

### 5.1 Handler that silently skips auditor emission

**SCENARIO:** a developer notices that a certain PATCH
scenario "doesn't feel important enough to audit" and writes
the handler to return `changed = false` even when changes
are persisted.

**GUARD:**
- Convention: `clinical-write-pattern.md` §6.4 — no-op
  suppression applies ONLY when NO change is persisted.
- Integration tests: every integration test with a
  successful mutation asserts exactly one matching audit
  row. A silently-suppressed success breaks the test.

**PROVING TEST:** `CreatePatientIntegrationTest.OWNER creates
patient — ... + audit row` and
`UpdatePatientDemographicsIntegrationTest.OWNER updates
nameMiddle and preferredLanguage — ... + audit row` both
assert `hasSize(1)` on the matching audit rows.

**SEVERITY:** HIGH. Missing audit rows are a direct
compliance failure.

### 5.2 Free-form PHI in the audit `reason` slug

**SCENARIO:** a developer writes
`reason = "patient ${command.nameFamily} updated"` in an
auditor.

**GUARD:** `GAP` — no ArchUnit rule inspects `AuditEventCommand`
construction for free-form strings.

**INDIRECT GUARD:**
- `PatientLogPhiLeakageTest` does NOT catch audit-row PHI
  because it only inspects log output, not DB state.
- An audit row with PHI in `reason` would not fail any
  existing test.

**SEVERITY:** HIGH. Audit rows are persisted, long-lived,
and exported to compliance reviewers. PHI in audit rows
means PHI in compliance exports.

**MITIGATION TODAY:** convention — `clinical-write-pattern.md`
§6.3 enumerates the allowed reason tokens (closed-enum slugs
only). Code review is the current guard.

**PROPOSED:** a new test that fires real POST + PATCH and
asserts audit rows' `reason` column against a regex that
matches only the approved token grammar:
```
intent:[a-z_.]+(\|[a-z_]+:[A-Z_,]+)*
```
Added as **pattern-gap finding G-2**.

### 5.3 Auditor uses wrong `AuditAction` entry

**SCENARIO:** a developer writes a new `PATIENT_MERGED`
auditor and accidentally emits `AuditAction.PATIENT_CREATED`.

**GUARD:** `GAP — runtime-only caught by integration test.`

**MITIGATION TODAY:** convention + integration test assertion
on `action` column.

**SEVERITY:** MED. The audit row would still be emitted, just
with the wrong action. Forensic queries would miss the event.

**NO PROPOSED NEW RULE** — ArchUnit can't inspect enum values
passed at runtime. This is a code-review concern.

---

## 6. Entity / boundary scenarios

### 6.1 Returning `PatientEntity` across a module boundary

**SCENARIO:** a developer writes a service method that returns
`PatientEntity` directly rather than constructing a
`PatientSnapshot`.

**GUARD:** `GAP — no ArchUnit rule prohibits entity return
types across packages.`

**MITIGATION TODAY:** convention + the fact that JPA entities
have JPA-specific annotations that should not survive
serialisation. If the entity is returned from a controller
method, Jackson would serialise its non-PHI + PHI fields
identically — so the wire shape could leak unintended fields
(e.g., `administrativeSexWire` instead of `administrativeSex`).

**SEVERITY:** MED. Could leak internal field names on the
wire. The class-level discipline is `never return entity from
service` but it's convention-only.

**PROPOSED:** a new ArchUnit rule — "no controller method
returns a type that is `@Entity`-annotated (transitively)."
Added as **pattern-gap finding G-3**.

### 6.2 `@ManyToOne` JPA relations on a new clinical entity

**SCENARIO:** a developer writes `patient_identifier.patient`
as a `@ManyToOne PatientEntity patient` relation, triggering
lazy loading across module boundaries.

**GUARD:** `GAP — convention only.`

**MITIGATION TODAY:** the Medcore convention (inherited from
tenancy) is bare-UUID FKs at entity boundaries. See
`PatientIdentifierEntity.kt` and its KDoc which explains this.

**SEVERITY:** LOW. Lazy-loading surprises are a bug class,
not a direct PHI leak.

**NO PROPOSED NEW RULE** — `@ManyToOne` is legitimate inside a
single aggregate; a blanket prohibition would over-fire.

---

## 7. PATCH / If-Match scenarios

### 7.1 New PHI PATCH endpoint without `If-Match`

**SCENARIO:** a developer adds a new patient endpoint (e.g.,
PATCH to update identifiers) and does not enforce `If-Match`.

**GUARD:** `GAP — no ArchUnit rule enforces If-Match on PATCH
endpoints.`

**MITIGATION TODAY:** convention — `clinical-write-pattern.md`
§7.2. Code review.

**SEVERITY:** MED. Missing If-Match means clients can blind-
overwrite PHI, racing with concurrent edits.

**PROPOSED:** an integration test pattern in the template's
§8.1 checklist — every PHI PATCH endpoint MUST have a test
case asserting 428 on missing If-Match and 409 on stale
If-Match. Future slices copy those two test cases into their
integration suite. Added as **pattern-gap finding G-4**
(documentation addition, not a new rule — ArchUnit can't
assert header handling).

### 7.2 `If-Match` comparison done at the DB layer only

**SCENARIO:** a developer removes the handler's
`expectedRowVersion` check, relying on JPA `@Version` at flush.

**GUARD:** runtime — `@Version` still produces
`ObjectOptimisticLockingFailureException` → 409
`resource.conflict` via 3G. But the error code drifts (the
generic "version mismatch" message replaces our specific
`stale_row` reason).

**MITIGATION TODAY:** the handler check's 409 uses
`details.reason = stale_row`. A JPA-only fallback uses no
`details.reason`. Integration tests assert
`details.reason = stale_row`.

**PROVING TEST:** `UpdatePatientDemographicsIntegrationTest.PATCH
with stale If-Match returns 409 stale_row`.

**SEVERITY:** LOW. Both paths are 409. Loss of diagnostic
specificity, not a correctness issue.

---

## 8. Validator bypass

### 8.1 Constructing a command directly in a test / future caller

**SCENARIO:** a test or future internal caller constructs a
`CreatePatientCommand` directly without going through the DTO
path — bypassing Bean Validation (but still running the
domain validator via the gate).

**GUARD:** the gate ALWAYS runs the validator. The DTO's Bean
Validation is only a bonus layer at the HTTP boundary.

**MITIGATION TODAY:** by design. The domain validator is the
authoritative check; DTOs exist for wire shaping, not for
security.

**SEVERITY:** LOW. Internal callers skipping Bean Validation
still face the domain validator.

### 8.2 Bypassing both by calling the handler directly

**SCENARIO:** a test or future caller calls
`createPatientHandler.handle(cmd, ctx)` directly, bypassing
both validator and policy.

**GUARD:**
- ArchUnit Rule 12 — `WriteGate.apply` is the only
  sanctioned entry. A `@Component` calling `handler.handle`
  directly from outside `..api..` would be flagged by a
  related rule (see **pattern-gap finding G-5**).

**MITIGATION TODAY:** the rule doesn't catch this specific
path. Convention + test review are current guards.

**SEVERITY:** HIGH. A handler-direct call skips authorization.
MEMBERs could create patients.

**PROPOSED:** extend ArchUnit Rule 12 (or add Rule 12b) to
restrict `{Command}Handler.handle` invocation to
`..write..` (where WriteGate's tx template calls them via
the lambda) + `..api..` (where the controller captures the
handler in the lambda). Added as **pattern-gap finding G-5**.

---

## 9. Summary grid

| # | Scenario | Guard | Severity | Status |
| ---- | ---- | ---- | ---- | ---- |
| 1.1 | Direct `repository.save()` | ArchUnit Rule 12 | HIGH | Covered |
| 1.2 | `writeGate.apply` from service | ArchUnit Rule 12 | MED | Covered |
| 1.3 | Handler `@Transactional` | Convention only | MED | **GAP G-1** |
| 2.1 | Clinical service missing `PhiSessionContext` | ArchUnit Rule 13 | HIGH | Covered |
| 2.2 | Wrong `WriteTxHook` (Tenancy vs Phi) | Runtime — RLS refusal | MED | Covered |
| 2.3 | Async bypass of filter chain | `PhiContextMissingException` | HIGH | Covered |
| 3.1 | `log.info(command)` | `PatientLogPhiLeakageTest` | HIGH | Covered |
| 3.2 | MDC with patient field | `PatientLogPhiLeakageTest` | HIGH | Covered |
| 3.3 | OTel span with patient field | `TracingPhiLeakageTest` | HIGH | Covered |
| 3.4 | Exception message with PHI value | `PatientLogPhiLeakageTest` | HIGH | Covered |
| 4.1 | Controller forgets `@AuthenticationPrincipal` | Compile / Spring | MED | Covered |
| 4.2 | Controller skips `X-Medcore-Tenant` | `PhiContextMissingException` | MED | Covered |
| 4.3 | `@PreAuthorize("permitAll()")` on PHI route | ArchUnit Rule 04 | HIGH | Covered |
| 5.1 | Handler silently skips auditor | Integration tests | HIGH | Covered |
| 5.2 | Free-form PHI in audit `reason` | Convention only | HIGH | **GAP G-2** |
| 5.3 | Wrong `AuditAction` entry | Integration test | MED | Covered |
| 6.1 | Controller returns `PatientEntity` | Convention only | MED | **GAP G-3** |
| 6.2 | `@ManyToOne` across aggregate | Convention only | LOW | No rule proposed |
| 7.1 | PHI PATCH without `If-Match` | Convention only | MED | **GAP G-4 (test-pattern addition)** |
| 7.2 | `@Version`-only stale detection | Integration test asserts `stale_row` | LOW | Covered |
| 8.1 | Bypassing Bean Validation by direct cmd | Domain validator | LOW | Covered |
| 8.2 | Bypassing validator + policy via handler-direct | Convention only | HIGH | **GAP G-5** |

---

## 10. Pattern-gap carry-forward ledger

The following gaps are documented as carry-forwards. Each is
eligible for a future hardening slice that adds the missing
guard.

| # | Gap | Proposed guard | Target close |
| ---- | ---- | ---- | ---- |
| **G-1** | `@Transactional` on clinical write-stack classes | ArchUnit rule: no `@Transactional` in `..clinical..write..` | Future hardening — when a real incident surfaces or 4A.3/4B introduces its own handlers |
| **G-2** | Free-form PHI tokens in audit `reason` column | Integration test asserting `reason` regex matches the approved token grammar | Same hardening slice as G-1 |
| **G-3** | Controller returning `@Entity`-annotated type | ArchUnit rule on controller return types | Future hardening |
| **G-4** | PHI PATCH endpoints without If-Match enforcement | Test-pattern addition to `clinical-write-pattern.md` §8.1 checklist (already present in v1.0) — plus a test helper that new slices copy in | **Already captured in template v1.0**; misuse catalog formalises it |
| **G-5** | Handler invocation outside WriteGate lambda scope | Extend ArchUnit Rule 12 to include `{Command}Handler.handle` invocation constraints | Future hardening |

Plus from §2.3 / 4A.0:
- **C-5** (carries from `phi-surface-audit-post-4a-2.md`):
  `PhiContextPropagator` async helper pattern when real async
  PHI path lands.

---

## 11. How to use this catalog

- **During code review** of a clinical write slice: walk §§1–8
  and verify each scenario is blocked. Flag any unaddressed
  gap as a review comment.
- **When adding a new clinical write slice**: the
  `clinical-write-pattern.md` §10 checklist already prevents
  most scenarios. This catalog is the "why" behind each
  checklist item — read it once per new feature to keep the
  gap ledger fresh.
- **When designing a new ArchUnit rule**: check this catalog
  for unresolved gaps. A rule that closes a gap here earns
  the slice double points.

---

## 12. Change log

| Version | Date | Change | Driven by |
| ---- | ---- | ---- | ---- |
| 1.0 | 2026-04-23 | Initial catalog extracted during 4A.2 stabilization | Phase 4A.2 stabilization pack |

Amendments to this catalog accompany new ArchUnit rules or
superseding ADRs. A scenario that gets a dedicated rule
moves from a GAP row to a Covered row.
