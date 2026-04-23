# PHI Surface Audit — post-4A.2 stabilization

**Type:** Adversarial post-ship audit, **distinct** from
`phi-exposure-review-4a-2.md` (which was pre-ship).
**Date:** 2026-04-23.
**Reviewer:** Repository owner (solo), stabilization pass.
**Scope:** Re-walk every PHI-touching surface in
`com.medcore.clinical.patient.*` with "what could leak or be
misused?" as the framing, not "does the code do what the design
said?". Paired with the Clinical Write Pattern template
(`docs/architecture/clinical-write-pattern.md` v1.0) and the
Misuse Catalog (`docs/architecture/clinical-misuse-catalog.md`).

Findings triage: **F = fix-in-slice now**, **C = carry-forward
tracked**, **M = no-action — mitigated by existing guard**.

---

## 0. Executive summary

- **0 F findings.** Nothing required a same-day code change.
- **4 C findings.** Tracked as carry-forwards for future slices.
  See §9.
- **11 M findings.** Each represents a place a developer might
  worry about. Every one is already mitigated by at least one
  existing guard — documented so future reviewers don't have to
  re-derive the reasoning.

The audit did surface **one important documentation fix**
(MRN counter column semantics — `next_value` is misleadingly
named, since after the upsert's RETURNING it holds the
most-recently-issued value, not the next one to issue). Not a
bug; docstring nit. Captured as finding M-11.

---

## 1. Log surface — re-walk

### 1.1 Enumeration

**Claim:** no clinical code emits Logger calls.

**Verification:** `grep -n 'log\.(info|warn|error|debug|trace)\b\|logger\.'`
against `com.medcore.clinical.*` returns exactly ONE match —
the KDoc string `"No \`log.info(body.toString())\`"` in
`PatientController.kt:64`. That's documentation, not a log
call. **Clinical code emits zero Logger sites.** (M-1)

**Why this matters:** the only places PHI could leak into
logs are:
- Framework log sites (Spring, Jackson, Hibernate, Hikari,
  Tomcat). These log class names + SQL, not field values — see
  §1.2 below.
- The `GlobalExceptionHandler.onUncaught` site, which logs an
  exception's stack trace. See §2.

### 1.2 Framework log sites

**Claim:** Hibernate SQL logging does not echo bound parameters
by default.

**Verification:** `application.yaml` does not set
`spring.jpa.properties.hibernate.type.descriptor.sql.BasicBinder`
= `TRACE`; `spring.jpa.show-sql` is also absent. Default
Spring Boot logging levels keep Hibernate at INFO, which emits
SQL templates with `?` placeholders but not bound values. (M-2)

**Residual concern:** an operator debugging in prod could
enable `BasicBinder TRACE` temporarily. If they do, patient
name/DOB values would appear in logs.

**Mitigation:** `docs/runbooks/observability.md` should call
this out as a PHI-exposure risk for any future on-call
rotation. **Captured as C-1** (runbook amendment).

### 1.3 The uncaught-exception handler

**Claim:** `GlobalExceptionHandler.onUncaught` logs the full
stack trace — which can include SQL, column names, and
sometimes row values.

**Verification:** `onUncaught(ex: Throwable)` calls
`log.error("uncaught exception in request handler", ex)`.
The `ex` argument's stack trace is the full chain including
causes. For a Postgres `DataIntegrityViolationException`, the
causal `PSQLException.getMessage()` is part of that trace.

**Adversarial scenario:** a CHECK constraint violation on
`clinical.patient.administrative_sex` — what does the PG error
message include?

PG's error format for CHECK violations is roughly:
```
ERROR:  new row for relation "patient" violates check
constraint "ck_clinical_patient_administrative_sex"
```
Constraint name + relation name only — **NO failing-row
values in the base error.** PG emits `DETAIL: Failing row
contains (…)` only at specific verbosity levels, and the JDBC
driver typically does NOT lift that to `SQLException.getMessage()`
by default.

**Residual concern:** JDBC driver or PG server configuration
could escalate. If a future operator sets
`log_min_error_statement = debug` or the JDBC driver's
`showErrorCause=true`, the DETAIL line could surface.

**Mitigation:** Rule 01 discipline already forbids enabling
verbose PG logging for clinical schemas in production. The
3G handler's response body is already fixed-string-only, so
even if the log line leaks, the HTTP response never does.
**Captured as C-2** (runbook reinforcement + optional future
scrubbing layer in `onUncaught`). (M-3 for the base case.)

### 1.4 MDC keys

**Claim:** MDC carries `request_id`, `user_id`, `tenant_id` —
allowlisted — and nothing else.

**Verification:** `RequestIdFilter` sets `request_id`.
`MdcUserIdFilter` sets `user_id` and `tenant_id`. No clinical
code adds MDC keys. `LogPhiLeakageTest` (3F.1) asserts no
patient PHI ends up in MDC. `PatientLogPhiLeakageTest` (4A.2)
provides clinical-field specific coverage. (M-4)

### 1.5 Tracing (OpenTelemetry) spans

**Claim:** no clinical code adds span attributes.

**Verification:** `grep -rn 'setAttribute\|addEvent\|@WithSpan'`
against `com.medcore.clinical.*` — no matches. 3F.2's
`TracingPhiLeakageTest` continues to cover the generic
invariant. **No PHI in spans.** (M-5)

### 1.6 Verdict — log surface

No F findings. One C finding (runbook amendment for operator
discipline around PG verbosity / Hibernate trace). Existing
guards hold.

---

## 2. Error path review — 3G envelope × 4A.2 interaction

### 2.1 Response-body discipline

**Claim:** no error response body carries PHI.

**Verification (per handler):**

| `@ExceptionHandler` | Status | Response body |
| ---- | ---- | ---- |
| `onAccessDenied` | 403 | `{code: "auth.forbidden", message: "Access denied.", requestId}` — fixed |
| `onWriteValidation` | 422 | `{…, details: {validationErrors: [{field, code}]}}` — field NAMES only, code is closed-enum slug, value never echoed |
| `onWriteConflict` | 409 | `{…, details: {reason: "<code>"}}` — closed-enum slug |
| `onOptimisticLock` | 409 | `{…}` — no details |
| `onDataIntegrityViolation` | 409 / 422 | `{…}` — no details (SQLSTATE-classified) |
| `onNotFound` | 404 | `{…}` — no details |
| `onDuplicatePatientWarning` | 409 | `{…, details: {candidates: [{patientId, mrn}]}}` — ID + MRN only, never name/DOB |
| `onPreconditionRequired` | 428 | `{…}` — no details |
| `onUncaught` | 500 | `{code: "server.error", message: "An unexpected error occurred.", requestId}` — fixed |

Every handler emits a `code` + fixed `message` + optional
`details` map whose shape is enumerated. There is no free-form
string path to the response body. (M-6)

### 2.2 Adversarial: forged duplicate-warning payload

**Adversarial scenario:** a caller submits a POST with a
DOB + family name they suspect exists, hoping the 409's
`details.candidates` will confirm. Can they escalate this
into a PHI-reading tool?

**Analysis:**
- Caller must hold `PATIENT_CREATE` authority (OWNER/ADMIN).
  A MEMBER cannot reach the detector.
- Response carries `{patientId, mrn}` only. No name, no DOB,
  no demographics. Caller learns "there is a patient in this
  tenant whose DOB + family matches what I submitted", plus
  its UUID and MRN. They do NOT learn that patient's actual
  name or any other field.
- Without a GET endpoint (4A.2 deferred GET to 4A.5), the
  caller cannot resolve `patientId` to any additional PHI.
- Once 4A.5 ships the GET endpoint + read-audit, that read
  will be AUDITED — so turning discovery into identification
  leaves a forensic trail.

**Residual:** a hostile OWNER/ADMIN can probe the tenant's
patient population by submitting crafted candidates. Rate
limiting would close this; deferred as **C-3**. (M-7 for the
minimal-disclosure gate that keeps this at "probe" level
rather than "extract".)

### 2.3 Adversarial: validator error code leaking field semantics

**Adversarial scenario:** the 422 envelope carries
`{field: "nameGiven", code: "control_chars"}`. Does this
leak domain info?

**Analysis:** the caller already knew they were sending a
`nameGiven` field. The `code` is a closed-enum slug. No
value is echoed. (M-8)

### 2.4 Adversarial: CHECK violation surfacing as uncaught

**Adversarial scenario:** a malformed `administrative_sex`
value that somehow bypasses the Kotlin enum converter reaches
the DB. The CHECK constraint fires. `DataIntegrityViolationException`
is thrown. 3G's `onDataIntegrityViolation` maps SQLSTATE
`23514` to 422 with fixed message — no constraint name, no
column name, no value.

**Verification:** `GlobalExceptionHandler.onDataIntegrityViolation`
lines 277–306. SQLSTATE classification is driver-independent;
response body is fixed-string. The LOG line carries the full
exception (see §1.3 concern) but the response body does not.
(M-9)

### 2.5 Verdict — error paths

No F findings. C-3 opened for rate-limiting dup-warning
enumeration. Existing error-envelope discipline is tight.

---

## 3. Duplicate-detection edge cases

### 3.1 Empty / whitespace-only family name

**Scenario:** can a caller bypass dup-detection by submitting
family-name = `" "`?

**Flow:**
- DTO binding — `@NotBlank` on `nameFamily` rejects null;
  Bean Validation in default mode rejects `""` but ACCEPTS
  `" "` depending on configuration.
- `CreatePatientValidator.validateNonBlank(value, "nameFamily")`
  — `value.trim().isEmpty()` catches `" "`. **Validator
  rejects with `blank`.**

**Verdict:** dup-detector never sees a whitespace-only family
name. (M-10)

### 3.2 Unicode / combining-character name

**Scenario:** caller submits `"Lovelace​"` (zero-width
space). Does this evade exact-match detection of a previously
stored `"Lovelace"`?

**Analysis:**
- SQL exact match uses `lower(name_family) = lower(?)`.
  `lower("Lovelace")` != `lower("Lovelace​")`. Exact
  match would miss.
- Soundex match uses `public.soundex(name_family) =
  public.soundex(?)`. `soundex('Lovelace​')` is not
  well-defined — Postgres's soundex treats non-ASCII per its
  implementation; a zero-width space is ignored in the
  consonant-coding pass, so `soundex('Lovelace​') =
  soundex('Lovelace')` in practice. **Phonetic match would
  catch it.**

**Residual:** some Unicode variants (e.g., full-width Latin)
might bypass both exact AND phonetic. This is a **false-
negative risk**, not a PHI leak risk — the duplicate detector
fails to warn on edge-case Unicode. Clinical workflow
accepts false negatives (duplicate record gets created;
human-reconciliation merge workflow handles it). **No
action.** (M-11)

### 3.3 Tenant A dup-probe lifting into tenant B

**Scenario:** can a caller with membership in tenant A use
the dup-detector to probe tenant B's patients?

**Analysis:**
- `X-Medcore-Tenant` header must match tenant A. Filter sets
  tenant context to A.
- Dup-detector queries filter by `tenant_id = ?` where the
  ID is A's tenant id (resolved from the slug in the command).
- Even if the SQL were wrong and queried the wrong tenant,
  V14's RLS on `clinical.patient` restricts visibility to the
  caller's ACTIVE-member tenant only. **Two independent
  gates. No cross-tenant leak.** (Already covered by
  `PatientSchemaRlsTest`; added here for completeness.)

### 3.4 Dup-detector called without GUCs set

**Scenario:** can a developer accidentally reach the detector
outside a WriteGate-owned tx, skipping the `PhiRlsTxHook`?

**Analysis:** `DuplicatePatientDetector.detect()` calls
`phiSessionContext.applyFromRequest()` as its first line.
- If the `PhiRequestContextHolder` is empty (no filter ran)
  → `PhiContextMissingException` → 500.
- If no active tx → `TenancySessionContext.apply`'s
  precondition check fires → `IllegalStateException` → 500.
Both are loud failures. **No silent zero-row reads on PHI.**
(Verified by the 4A.0 test suite; restated here.)

### 3.5 Verdict — duplicate detection

No F findings. Edge-case false-negative on exotic Unicode is
acceptable (human workflow handles it). All leak paths
blocked by independent gates.

---

## 4. PATCH null-semantics misuse

### 4.1 The three-state grid

| Wire shape | JsonNode state | `Patchable<T>` value | Handler action |
| ---- | ---- | ---- | ---- |
| field absent | `!body.has(field)` | `Absent` | unchanged |
| `{"f": null}` | `body.get(field)` isNull | `Clear` | write NULL (or validator refuses on required column) |
| `{"f": "v"}` | textual | `Set("v")` | write value |
| `{"f": ""}` | textual, empty | `Set("")` | validator rejects `blank` |
| `{"f": "  "}` | textual, whitespace | `Set("  ")` | validator rejects `blank` (trim-check) |
| `{"f": false}` | not textual | n/a | mapper throws `not_string` → 422 |
| `{"f": 42}` | numeric | n/a | mapper throws `not_string` → 422 |
| `{"f": []}` | array | n/a | mapper throws `not_string` → 422 |
| `{"f": {}}` | object | n/a | mapper throws `not_string` → 422 |

### 4.2 Adversarial: wrong JSON type for date field

**Scenario:** `{"birthDate": 19000101}` — caller sends a
numeric literal.

**Flow:**
- `datePatch(body, "birthDate")` — `node.isTextual` is false
  → throws `WriteValidationException(field = "birthDate",
  code = "not_date")` → 422.

**No leak, no silent acceptance.** (M-12)

### 4.3 Adversarial: required-column null-wipe

**Scenario:** `{"nameGiven": null}` — caller tries to clear a
NOT-NULL column.

**Flow:**
- Mapper returns `Patchable.Clear`.
- Validator's `refuseClear(command.nameGiven, "nameGiven")`
  throws `WriteValidationException(field = "nameGiven", code
  = "required")` → 422.

**Defence in depth:** even if the validator missed (bug), the
DB's NOT NULL would fire on save → `DataIntegrityViolationException`
→ SQLSTATE 23502 → 422 via 3G. Two-layer.

### 4.4 Adversarial: empty-body PATCH

**Scenario:** `{}` — caller wants to bump `row_version`
without changing anything.

**Flow:**
- Every `Patchable.{name}` is `Absent`. `changingFieldNames()`
  returns empty set.
- Validator's `if (command.changingFieldNames().isEmpty())
  throw WriteValidationException(field = "body", code =
  "no_fields")` → 422.

**No silent no-op.** Caller learns the PATCH rejected.

### 4.5 Adversarial: smuggle an immutable field

**Scenario:** `{"mrn": "BADGUY-0001"}` — caller tries to
force MRN change.

**Flow:**
- `UpdatePatientDemographicsRequestMapper` has no case for
  `mrn`. The field is silently dropped.
- `changingFieldNames()` does NOT include `mrn`.
- Handler applies only the (empty) patchable set.
- If no other fields were sent, `no_fields` fires (422).
- If other valid fields were sent, the `mrn` smuggle attempt
  is simply ignored — the persisted row retains its original
  MRN.

**No leak, no privilege escalation.** (M-13)

### 4.6 Verdict — PATCH null semantics

No F findings. The three-state grid is tight; every
deviation the audit tried is blocked by either the mapper or
the validator.

---

## 5. DTO → entity mapping

### 5.1 Enum conversion surfaces

**Claim:** `administrativeSex` wire-value parsing cannot allow
a bad value through to the entity.

**Verification:**
- DTO→command conversion: `AdministrativeSex.fromWire(rawSex)`
  either returns a valid enum or throws `IllegalStateException`
  (caught → `WriteValidationException` → 422). **No bad value
  reaches the command.**
- Command→entity conversion: `entity.administrativeSexWire =
  command.administrativeSex.wireValue`. `.wireValue` on a
  valid enum is always a valid wire string. **No bad value
  reaches the entity.**
- Entity→DB conversion: direct column assign. DB CHECK
  constraint would catch any bad value that somehow bypassed
  the above — SQLSTATE 23514 → 422. **Third line of defence.**

Three independent gates. No path to corrupt state. (M-14)

### 5.2 Entity mutation outside the write stack

**Adversarial scenario:** a future developer calls
`patientEntity.administrativeSexWire = "bogus"` directly.

**Analysis:**
- `patientEntity` is a `var` field — mutable by design (JPA
  requires). Kotlin doesn't prevent the assignment.
- BUT: the property `administrativeSex: AdministrativeSex`
  reads via `fromWire()` which throws on bad values. So any
  read of the bad-written field fails loudly.
- And: JPA's dirty-check would flush the bad string; the DB
  CHECK constraint refuses it at flush time.

**Residual:** a developer could technically write a bad
string to the entity, never read it, never flush (cancel the
tx). This is implausible but not structurally impossible.
**No practical exposure.**

### 5.3 Verdict — mapping

No F findings. Three independent gates (converter, typed
accessor, DB CHECK) prevent bad values from persisting.

---

## 6. Response-DTO serialization

### 6.1 `PatientResponse.from(snapshot)`

**Claim:** every field on the response DTO is explicitly
named + built from the snapshot — no reflection, no
`@JsonAnyGetter`, no surprise fields.

**Verification:** `PatientResponse.Companion.from()` lines
213–232. Every field is an explicit assignment from a
`snapshot.*` accessor. No `Map<String, Any>` fields, no
`Object` fields. (M-15)

### 6.2 `@JsonInclude(NON_NULL)`

**Claim:** nullable fields unset on the snapshot are omitted
from the wire (not emitted as `null`).

**Verification:** class-level `@JsonInclude(JsonInclude.Include.NON_NULL)`
on `PatientResponse`. Jackson honours. (M-16)

### 6.3 Incidental finding: `rowVersion` Long type on the wire

**Observation:** `rowVersion` is serialized as a JSON number
(Long). JavaScript clients using `parseFloat` might get
precision issues at `Long.MAX_VALUE`. In practice `row_version`
won't exceed a few hundred in the lifetime of a row.

**No action.** Documented here for future reference.

### 6.4 Verdict — response serialization

No findings. Clean.

---

## 7. MRN generator semantics re-walk

### 7.1 `next_value` column name

**Finding M-17 (documentation fix):** the column name
`next_value` on `clinical.patient_mrn_counter` is semantically
misleading. After `INSERT ... ON CONFLICT DO UPDATE ...
RETURNING next_value`, the returned value is the
**most-recently-issued** MRN number, NOT "the next value to
issue". The invariant that holds is:

```
After N successful mints in a tenant, next_value = N.
The most recently returned (and issued) MRN is also N.
The next mint will return N+1.
```

**Not a bug.** The code produces correct monotonic MRNs.
The column name is confusing for future readers.

**Action:** flag as documentation amendment candidate for
the next migration (no retroactive rename — V15 is
committed). **Captured as C-4** — future MRN-generator KDoc
clarification.

### 7.2 Rollback safety — verified

`MrnGeneratorTest` covers two distinct rollback scenarios
(bootstrap rollback + post-commit rollback). Both pass. No
residual concern.

### 7.3 Concurrency — verified

`PatientCreateConcurrencyTest` — 50 parallel creates yield
contiguous `000001..000050` with no gaps / duplicates. No
residual concern.

### 7.4 Counter visibility to non-privileged roles

**Claim:** the counter row itself is tenant-scoped + ACTIVE-
membership-gated via V15 RLS. A non-member cannot probe the
counter's current value.

**Verification:** V15 `p_patient_mrn_counter_select` policy
requires ACTIVE membership. Non-members + suspended members
see zero rows. (M-18)

---

## 8. Summary table

| # | Area | Type | Disposition |
| ---- | ---- | ---- | ---- |
| M-1 | Clinical code has no `Logger` sites | Observation | No action |
| M-2 | Hibernate SQL logging does not echo bound values by default | Observation | No action |
| M-3 | PG error-message DETAIL line does NOT include row values by default | Observation | C-2 runbook reinforcement |
| M-4 | MDC carries only allowlisted keys | Observation | No action |
| M-5 | OpenTelemetry spans carry no PHI | Observation | No action |
| M-6 | Every `@ExceptionHandler` emits closed-enum shape | Observation | No action |
| M-7 | Duplicate-warning candidate shape is minimal-disclosure | Observation | C-3 rate-limit opens |
| M-8 | Validator codes are closed-enum; no value leak | Observation | No action |
| M-9 | CHECK violation → 422 fixed-string message | Observation | No action |
| M-10 | Whitespace-only family name rejected before dup-detect | Observation | No action |
| M-11 | Exotic Unicode may bypass both dup-matchers | Acceptable false-negative | No action |
| M-12 | Wrong JSON type for date → 422 `not_date` | Observation | No action |
| M-13 | Immutable-field smuggling in PATCH body is silently dropped | Observation | No action |
| M-14 | Enum wire-value bad values blocked at 3 gates | Observation | No action |
| M-15 | Response DTO uses explicit field mapping | Observation | No action |
| M-16 | Nullable unset fields omitted from wire | Observation | No action |
| M-17 | `next_value` column name semantically misleading | Doc nit | C-4 future KDoc amendment |
| M-18 | MRN counter rows tenant-scoped by RLS | Observation | No action |

---

## 9. Carry-forwards opened by this audit

| # | Item | Rationale for deferral | Target close |
| ---- | ---- | ---- | ---- |
| **C-1** | Runbook amendment — operator discipline around Hibernate SQL tracing + PG log verbosity on clinical schemas | Observability runbook exists (`docs/runbooks/observability.md`); add a "PHI considerations" section | Next runbook-edit slice or 4A.5 with read-audit |
| **C-2** | Optional stack-trace scrubbing layer in `GlobalExceptionHandler.onUncaught` | Current base case is safe (PG doesn't emit row DETAIL by default); layer is belt-and-braces | Future hardening slice if operator config review reveals any verbose-tracing path active |
| **C-3** | Rate-limiting on duplicate-warning endpoint to close the enumeration residual | Authority gate + minimal-disclosure payload are primary mitigations; rate limit is secondary | Future hardening slice when abuse observed |
| **C-4** | `MrnGenerator` / V15 KDoc amendment clarifying `next_value` column semantics | Docs-only; no runtime impact | Next migration touching the counter |

---

## 10. Conclusion

Phase 4A.2 shipped a PHI surface that holds up under
adversarial re-read. Zero fix-in-slice findings means the
4A.2 design was sound; no secret bugs were hiding.

The four carry-forwards (C-1 through C-4) are all low-priority
hardening items — none block future slices. They represent
"polish the edges" work that any system accumulates as it
matures.

The template (`clinical-write-pattern.md` v1.0) locks in the
pattern as shipped. The misuse catalog
(`clinical-misuse-catalog.md`) proves each deviation is
blocked.

**Audit sign-off: GREEN.** Stabilization pack complete. Safe
to proceed to 4A.3 (identifiers) after the misuse catalog
lands.

---

*Generated during Phase 4A.2 stabilization (2026-04-23).
Amendments to this audit follow the same ADR discipline as
the pattern template.*
