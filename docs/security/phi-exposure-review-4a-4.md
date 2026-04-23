# PHI Exposure Review — Phase 4A.4 (Patient Read + Read Audit)

**Slice:** Phase 4A.4 — Medcore's **first PHI READ endpoint**.
Ships `GET /api/v1/tenants/{slug}/patients/{patientId}` with
HIPAA §164.312(b) compliant audit emission. Introduces the
`ReadGate` substrate (sister to `WriteGate`) and two new
`AuditAction` entries (`CLINICAL_PATIENT_ACCESSED` +
`AUTHZ_READ_DENIED`). Renames `WriteResponse<T>` →
`ApiResponse<T>` to establish a single canonical response
envelope across reads and writes.

DoD §3.8.4/§3.8.5 reconciled: 4A.4 is now the read-audit
slice; FHIR Patient mapping slides to 4A.5.

**Reviewer:** Repository owner (solo).
**Date:** 2026-04-23.
**Scope:** all 4A.4 code + no migrations + tests + governance
docs. Three new framework interfaces (`ReadGate`,
`ReadAuthzPolicy`, `ReadAuditor`), one command stack (Get),
one new rule (ArchUnit Rule 14), two new audit actions, one
type rename (`WriteResponse`→`ApiResponse`), three rule
allowlist updates (Rule 1, Rule 9, Rule 11) to permit `.read`
packages.

**Risk determination:** **Low.** First PHI READ introduces
novel attack surface (PHI disclosure on every 200), but the
ReadGate's runtime audit-atomicity (ADR-003 §2 extended to
reads) + V14 RLS + filter chain + policy + ArchUnit Rule 14
compose into a defence-in-depth stack symmetric to writes.
The one real design finding (read tx cannot be Postgres
read-only because the audit INSERT needs to share the tx)
was surfaced during test execution and documented in the
ReadGate KDoc.

---

## 0. Pattern-reuse report (vs `clinical-write-pattern.md` v1.1)

Per user directive, each implementation step references the
§10 checklist. Result:

| Checklist item | 4A.4 disposition |
| ---- | ---- |
| Schema migrations | **N/A** — no new migration; V14's SELECT policy already covers the read path |
| Command stack | `GetPatientCommand` + Policy + Handler + Auditor — all follow the §§2–6 pattern (adapted for reads) |
| Authority registry | `PATIENT_READ` reused — no new `MedcoreAuthority` entry |
| Audit registry | **+2 entries** with NORMATIVE shape KDoc (`CLINICAL_PATIENT_ACCESSED` + `AUTHZ_READ_DENIED`) |
| HTTP surface | `GET /api/v1/tenants/{slug}/patients/{patientId}` appended to existing `PatientController` |
| Error envelope | **No new codes.** 404 / 403 / 500 all existing |
| Tests (min) | Validator N/A (reads have no body); Policy unit N/A (pass-through today, integration covers); Integration ✓; Concurrency N/A (no serialisation concern); Rollback N/A (no cross-table side effect); PHI log-leakage ✓ |
| Test hygiene | No new resets needed |
| Governance | This doc + DoD + roadmap + template v1.1 → v1.2 |
| Architecture discipline | Rule 14 added; Rules 1, 9, 11 extended to allow `.read` packages |
| Final gate | 434/434 tests green (+16) |

**Zero silent drift.** Every deviation from 4A.2 was
deliberate and documented:
- No validator (reads have no body to validate)
- Not-read-only tx (audit INSERT requires a writable tx)
- `ReadAuthzPolicy` / `ReadAuditor` as sister interfaces, NOT type-aliases of Write counterparts (semantic clarity)

---

## 1. Framework additions

### 1.1 `ReadGate<CMD, R>` — new substrate

New class at `com.medcore.platform.read.ReadGate`. Sister to
`WriteGate`:

| Step | Shape |
| ---- | ---- |
| 1. Authorize | `ReadAuthzPolicy.check` throws `WriteAuthorizationException` on deny |
| 2. Open tx | `PROPAGATION_REQUIRED`; NOT read-only (audit INSERT needs a writable tx) |
| 3. Pre-exec hook | `WriteTxHook` reused (`PhiRlsTxHook` sets both GUCs) |
| 4. Execute | Controller's lambda runs the handler |
| 5. Audit-success | `ReadAuditor.onSuccess` emits INSIDE tx (ADR-003 §2 extended to reads) |
| Denial | `ReadAuditor.onDenied` runs in its own tx (via `JdbcAuditWriter`'s `PROPAGATION_REQUIRED`) |

### 1.2 `ReadAuthzPolicy<CMD>` + `ReadAuditor<CMD, R>` — sister interfaces

Structurally identical to their Write counterparts. Retained
as separate interfaces for semantic clarity: a reader reading
`ReadAuditor` knows they're looking at read-path logic.

### 1.3 `WriteContext` + `WriteTxHook` — REUSED

No new types. `WriteContext(principal, idempotencyKey)` is
operation-agnostic; the idempotency-key field is unused for
reads (nullable default). `WriteTxHook` is just a pre-execute
hook — no write-specific semantic.

A cross-cutting rename of both to operation-neutral names is
tracked as a separate, optional cleanup. Not 4A.4 scope.

### 1.4 `WriteResponse<T>` → `ApiResponse<T>` — rename

Per user directive #3: "do NOT split ReadResponse<T>; single
canonical envelope." Rename touches 3 files:
- `ApiResponse.kt` (new file; old `WriteResponse.kt` deleted)
- `TenantsController.kt` — 7 usages updated
- `PatientController.kt` — 7 usages updated (incl. new GET)

Wire shape unchanged (`data` + `requestId`). Client-visible
breaking change: **none**.

### 1.5 ArchUnit Rule 14

New file `ReadBoundaryArchTest.kt`. Rule 14 restricts
`ReadGate.apply` to `..api..` + `..read..` (test adjacency).
Mirrors Rule 12's allowance structure exactly.

### 1.6 ArchUnit Rule 1, 9, 11 — `.read` package allowlisted

Three existing rules extended to permit `.read` packages:
- **Rule 1** (`MutationBoundaryArchTest.repositories_accessed_only_from_sanctioned_layers`) — `.read` handlers legitimately access `TenantRepository` / `PatientRepository` during read flows.
- **Rule 9** (`SecurityDisciplineArchTest.audit_event_command_constructed_only_in_sanctioned_layers`) — `ReadAuditor` implementations in `.read` construct `AuditEventCommand`.
- **Rule 11** (`SecurityDisciplineArchTest.audit_action_references_in_sanctioned_layers_only`) — `.read` packages hold `AuditAction` references for `CLINICAL_PATIENT_ACCESSED` / `AUTHZ_READ_DENIED`.

All three additions are non-breaking and documented inline.

### 1.7 Two new `AuditAction` entries

`CLINICAL_PATIENT_ACCESSED` (`clinical.patient.accessed`) —
emitted ONLY on 200 OK reads; NEVER on 404 or 500.
Compliance-critical: HIPAA §164.312(b) requires logging
disclosures; 404/500 are not disclosures.

`AUTHZ_READ_DENIED` (`authz.read.denied`) — distinct from
`AUTHZ_WRITE_DENIED` so compliance queries can separate
read-denials (information-disclosure intent) from
write-denials (mutation intent).

---

## 2. Data-flow review

### 2.1 HTTP entry

`@GetMapping("/{patientId}")` on `PatientController`.
Principal via `@AuthenticationPrincipal`; tenant context via
existing `TenantContextFilter`; PhiRequestContext via
existing `PhiRequestContextFilter`.

No body, no headers beyond the baseline. `Idempotency-Key`
not accepted on GET (idempotent by HTTP spec).

### 2.2 Policy

`GetPatientPolicy` checks `PATIENT_READ`. All three roles
hold it per the 4A.1 role map; this is pass-through today.
Kept for pattern symmetry + future role-differentiation
(ADR-prompt: when `PATIENT_READ_FULL` / `PATIENT_READ_SUMMARY`
land, the policy is where that splits).

### 2.3 Transaction open + PhiRlsTxHook

ReadGate opens a writable `PROPAGATION_REQUIRED` tx.
`PhiRlsTxHook.beforeExecute` sets both RLS GUCs in-tx.

### 2.4 Handler

`GetPatientHandler`:
1. Load tenant by slug (RLS-gated; caller has `PATIENT_READ`
   so tenant is visible).
2. Load patient by id (RLS-gated; V14 `p_patient_select`
   hides DELETED, cross-tenant, non-member rows). 404 if
   absent.
3. Belt-and-braces `patient.tenantId == tenant.id` check.
4. Build `PatientSnapshot` (reused from 4A.2).

### 2.5 Auditor

`GetPatientAuditor.onSuccess` emits
`CLINICAL_PATIENT_ACCESSED` row INSIDE the read tx. Audit
failure → tx rollback → response body never serialised →
caller sees 500, NO PHI disclosed. (Verified by
`ReadGateTest.onSuccess throws — transaction rolls back,
handler result never delivered`.)

`GetPatientAuditor.onDenied` emits `AUTHZ_READ_DENIED`
outside the read tx (denial path never opened one), via
`JdbcAuditWriter`'s `PROPAGATION_REQUIRED` own-tx.

### 2.6 Response

`ApiResponse<PatientResponse>` + `ETag: "<rowVersion>"`
header. `PatientResponse.from(snapshot)` shapes the body —
reused from 4A.2.

### 2.7 Error paths

- **401** (no bearer) — Spring Security default entry point.
- **403 filter-level** (SUSPENDED / not-a-member) —
  `TenantContextFilter` refuses; emits
  `tenancy.membership.denied` (existing from 3B.1). Policy
  never runs. **No `CLINICAL_PATIENT_ACCESSED` emitted.**
- **403 policy-level** (lacks `PATIENT_READ`) — rare today;
  future-proofed. Emits `AUTHZ_READ_DENIED`.
- **404** (unknown id, cross-tenant, DELETED) — handler
  throws `EntityNotFoundException`; 3G maps to 404. **No
  audit emitted** (no disclosure happened).
- **500** (any other exception, including audit-write
  failure) — 3G `onUncaught` → fixed-string body. No audit
  emitted.

---

## 3. Design finding surfaced during execution

**Read tx cannot be Postgres read-only.**

Initial design set the ReadGate's tx as `readOnly = true` for
defence in depth. At runtime this broke: PG refuses INSERT
under `SET TRANSACTION READ ONLY`, so the audit row couldn't
be written.

**Resolution:** tx is writable; V14+ RLS enforces "no
mutations from read handlers" at the policy layer. The
"read-only" property was defensive layering, not correctness.
Documented in `ReadGate.kt` KDoc + the class's tx-template
comment. Tracked as template v1.2 §12 note.

---

## 4. Attack-surface considerations

### 4.1 PHI disclosure without audit

**Prevented.** Audit emission is in-tx with the read. Audit
failure → tx rollback → response body never serialises.
Verified by `ReadGateTest` (synthetic audit-throw proof).

### 4.2 Cross-tenant id-probing

**Prevented.** Handler's `patient.tenantId != tenant.id` →
`EntityNotFoundException` → 404 (identical to unknown id).
RLS also blocks at DB layer. No existence leak. Verified:
`GetPatientIntegrationTest.cross-tenant patientId — 404 with
NO access audit row`.

### 4.3 DELETED patient disclosure

**Prevented.** V14's `p_patient_select` excludes
`status = 'DELETED'`. Handler's `findById` returns empty →
404. Verified: `GetPatientIntegrationTest.DELETED patient —
404`.

### 4.4 Audit-row enumeration via 404s

**Not applicable.** 404 doesn't emit
`CLINICAL_PATIENT_ACCESSED` (no disclosure), so an attacker
cannot probe for patient existence by reading back the audit
chain. (Separately: the audit chain is not reader-accessible
in 4A.4 — read-audit consumption is a future forensic UI
slice.)

### 4.5 ReadGate.apply bypass

**Prevented by ArchUnit Rule 14.** Only `.api` (+ `.read`
test adjacency) may call `ReadGate.apply`. Services /
filters / auditors / policies cannot open a second read
entry point.

### 4.6 PHI in logs / MDC / spans

**Prevented.** `PatientReadLogPhiLeakageTest` fires a GET
with distinctive synthetic tokens and grep-asserts captured
stdout. No clinical log sites added. MDC carries only
allowlisted keys (request_id, user_id, tenant_id).

### 4.7 Missing PhiSessionContext call (forgotten
`applyFromRequest`)

**Prevented.** The bean wiring pins `PhiRlsTxHook` on every
patient gate (write + read). Rule 13 catches the case where
a clinical service forgets the dependency at compile time.
The gate wiring IS the runtime guard.

### 4.8 Read-tx writable → handler accidentally mutates

**Mitigated by** V14+ RLS policies on PHI tables gating
writes on OWNER/ADMIN role. A read handler that
accidentally mutates would be refused at WITH CHECK time.
Not prevented at tx-level (we removed the read-only flag),
but the RLS layer is the authoritative gate.

**Residual:** a code-review-only concern. Tracked as
carry-forward: consider a future ArchUnit rule enforcing
"no `save*` calls in `.read` handlers" to catch accidental
mutations at compile time.

### 4.9 ApiResponse rename — client breaking

**Not breaking.** Wire shape (`{data, requestId}`) is
identical. Clients see unchanged JSON.

### 4.10 Audit-chain ordering under concurrent reads

**Correct.** `audit_event.sequence_number` + hash-chain
(V9) are append-only serialised. Concurrent reads on the
same patient produce distinct audit rows in sequence order;
forensic query for "who accessed patient X?" returns all
rows regardless of concurrency.

### 4.11 Handler returning after audit failed

**Prevented.** `TransactionTemplate.execute` throws if the
lambda throws; the return value is never delivered to the
caller. Verified: `ReadGateTest.onSuccess throws — transaction
rolls back, handler result never delivered`.

---

## 5. Template v1.2 amendments (to land in this slice)

Three non-breaking additions to
`docs/architecture/clinical-write-pattern.md`:

1. **New §12 "Read path"** — covers ReadGate + ReadAuthzPolicy
   + ReadAuditor + the not-read-only-tx note + the
   200-only-audit emission rule.
2. **§10 checklist additions** for read endpoints — "new
   AuditAction entries for read?", "200-only emission?",
   "PHI-leakage test on GET too?", "`AUTHZ_READ_DENIED`
   emitted on policy denial?".
3. **§7.1 symmetry note** — @GetMapping endpoints wire
   `ReadGate`, not `WriteGate`.

ArchUnit Rule 14 documented in §9 file map (not a template
change; a reference addition).

---

## 6. Test coverage summary

| Suite | Tests | Purpose |
| ---- | ---- | ---- |
| `ReadGateTest` | 5 | Substrate pipeline — success order, policy denial, onSuccess-throws rollback, denial-audit-failure handling, null-hook |
| `GetPatientIntegrationTest` | 9 | HTTP→DB happy, ADMIN / MEMBER read, 401, 404 (unknown / cross-tenant / DELETED), filter-level 403 (no-member / SUSPENDED), NO audit emitted on 404 |
| `PatientReadLogPhiLeakageTest` | 1 | GET with distinctive PHI tokens; no tokens in stdout |
| `MutationBoundaryArchTest` (Rule 1 extended) | existing | `.read` allowlisted for repository access |
| `SecurityDisciplineArchTest` (Rules 9, 11 extended) | existing | `.read` allowlisted for audit-action + audit-event-command usage |
| `ReadBoundaryArchTest` (Rule 14) | 1 | ReadGate.apply only from `..api..` + `..read..` |

**Total 4A.4 new tests:** 16.
**Suite total:** 418 → 434.

---

## 7. Carry-forwards closed by 4A.4

- **"GET endpoint deferred to 4A.5"** (4A.2 PHI review) — closed.
- **"Read-audit infrastructure"** (4A.0 note) — closed.
- **ArchUnit Rule 13's original read-path intent** — fully
  demonstrated now (the handler + service imports for
  PhiSessionContext via PatientReadService are still TBD but
  4A.4 proved the PhiRlsTxHook mechanism works for reads).

## 8. Carry-forwards opened by 4A.4

| # | Item | Rationale | Target close |
| ---- | ---- | ---- | ---- |
| CF-4A4-1 | `If-None-Match` / 304 Not Modified support for PHI GETs | Caching optimization; not MVP | Future slice if bandwidth matters |
| CF-4A4-2 | Partial-read modes (summary vs full) with `mode:` token in audit reason | Medical workflow may demand summary-only reads with different audit semantics | Slice if demanded |
| CF-4A4-3 | Anomalous-404 detection (turning audit logs into probe-detection signal) | Different (legitimate) feature; out of 4A.4 scope | Future security-tooling slice |
| CF-4A4-4 | List endpoint `GET /patients` with pagination + search | 4A.4 is single-id read; list is its own slice | 4A.6 or later |
| CF-4A4-5 | ArchUnit rule enforcing "no `save*` calls in `.read` handlers" | Belt-and-braces over RLS; low priority | Future hardening slice |
| CF-4A4-6 | Cross-cutting rename of `WriteContext`/`WriteTxHook` to operation-neutral names | Quality-of-life; no correctness impact | Cleanup slice when multiple reads ship |

---

## 9. Conclusion

Phase 4A.4 shipped the first PHI READ endpoint in Medcore
with full HIPAA §164.312(b) compliance. The `ReadGate`
substrate composes symmetrically with `WriteGate`: same
audit-atomicity contract, same ArchUnit enforcement, same
run-time discipline.

The rename to `ApiResponse<T>` establishes a single canonical
envelope — no fragmentation.

One real design finding surfaced (tx cannot be PG read-only
when audit INSERT must share the tx); resolved cleanly and
documented.

Pattern template extends to v1.2 with a non-breaking §12
"Read path" addendum.

**Audit sign-off: GREEN.** Proceed to Phase 4A.5 (FHIR
`Patient` mapping) per the reconciled DoD ordering.

---

*Generated during Phase 4A.4 execution (2026-04-23).*
