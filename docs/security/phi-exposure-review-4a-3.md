# PHI Exposure Review — Phase 4A.3 (patient identifiers)

**Slice:** Phase 4A.3 — first pattern-validation reuse of the
`clinical-write-pattern.md` v1.0 baseline extracted from 4A.2.
Ships `POST /api/v1/tenants/{slug}/patients/{patientId}/identifiers`
and `DELETE /api/v1/tenants/{slug}/patients/{patientId}/identifiers/{identifierId}`.

V14's identifier satellite schema was already in place from
4A.1; 4A.3 adds the write surface on top. V17 migration
closes a real V14 defense-in-depth gap (role gate missing
from identifier RLS write policies).

**Reviewer:** Repository owner (solo).
**Date:** 2026-04-23.
**Scope:** all 4A.3 code + V17 migration + tests + governance
docs. Two command stacks (Add + Revoke), two new AuditAction
entries, two new controller endpoints, one new migration, four
new test suites + extension of `PatientSchemaRlsTest`.

**Risk determination:** **Low.** Second clinical PHI write
surface in Medcore; defence-in-depth verified across three
layers (TenantContextFilter → Policy → V17 RLS WITH CHECK).
Identifier `value` is PHI for DL/Insurance — log leakage test
proves it never reaches logs. Pattern reuse was clean — no
architectural surprises surfaced.

---

## 0. Pattern-reuse report

This is the first slice to explicitly follow the v1.0 pattern
as a forcing function. Outcome against the §10 checklist:

| Checklist block | Result |
| ---- | ---- |
| Schema & migrations | Satisfied via V17 (RLS tightening only; core table was already in V14) |
| Command stack | Two stacks (Add + Revoke); both follow §§2–6 exactly |
| Authority + audit registry | `PATIENT_UPDATE` reused; 2 new AuditAction entries with NORMATIVE shape KDoc |
| HTTP surface | Endpoints appended to existing `PatientController`; mirrors TenantsController precedent |
| Error envelope | No new codes (UNIQUE / not-found covered by existing handlers) |
| Tests (minimum) | 4 new suites (Add, Revoke, Validator, LogLeakage) + PatientSchemaRlsTest extended; Concurrency + Rollback N/A for identifiers |
| Test hygiene | No new resets needed — identifier cleanup already in every test (from 4A.1) |
| Governance | This doc + DoD §3.8.4 populated + roadmap ledger refreshed |
| Architecture discipline | All 13 ArchUnit rules still pass |
| Final gate | 418/418 tests green, full suite |

**Zero silent drift.** Every deviation from 4A.2 was a
deliberate, logged choice:
- No PATCH (identifiers don't support update in 4A.3 — flagged
  in §4.5)
- No `If-Match` (DELETE is idempotent — precedent from 3J.N)
- No concurrency test (no DB serialisation concern)
- No rollback test (no cross-table side effect)
- No new error code (UNIQUE suffices)

These are listed in the master plan as N/A and documented in
the template as `[INCIDENTAL]` exceptions.

---

## 1. What this slice handles

### 1.1 PHI fields touched

| Field | Type | PHI classification |
| ---- | ---- | ---- |
| `type` | Closed enum | **Not PHI** — 4-entry closed set |
| `issuer` | String | **Not PHI but implementation-detail sensitive** (payer name, state abbreviation) |
| `value` | String | **PHI** for DRIVERS_LICENSE / INSURANCE_MEMBER — identifier-style data element per 45 CFR §164.514(b)(2); potentially PHI for OTHER depending on usage; less sensitive for MRN_EXTERNAL (cross-system tenant-scoped reference) |
| `validFrom` / `validTo` | Instant | Temporal metadata; not PHI by itself |

### 1.2 Non-PHI

- `id`, `patient_id`, `tenant_id` — UUIDs
- `row_version`, `created_at`, `updated_at` — audit/concurrency state
- `slug` — caller-facing tenant identifier

### 1.3 Deliberately out of scope

- **UPDATE identifier (change value).** Ships as 4A.3.1 if a
  pilot workflow demands correction-without-revoke-readd.
- **LIST / GET identifiers.** Ships with 4A.5 read + read-audit.
- **SSN.** Not in `PatientIdentifierType`; dedicated future slice.
- **IMPORTED identifier source.** No separate flag; all
  4A.3 adds are caller-minted.

---

## 2. Data-flow review

### 2.1 HTTP entry — controller binding

**POST `.../identifiers`:**
- `@AuthenticationPrincipal MedcorePrincipal` via Spring Security.
- `@Valid @RequestBody AddPatientIdentifierRequest` — Bean
  Validation rejects null/blank on `type`, `issuer`, `value` at
  MVC boundary → 422.
- `X-Medcore-Tenant` REQUIRED — `TenantContextFilter` resolves,
  `PhiRequestContextFilter` promotes, `PhiRlsTxHook` sets both
  GUCs in-tx.
- `Idempotency-Key` — shape-only (no dedupe, consistent with
  4A.2 discipline).

**DELETE `.../identifiers/{identifierId}`:**
- Same principal/tenant/idempotency handling.
- No body; path variables only. `@PathVariable UUID` rejects
  malformed UUIDs via Spring MVC (→ 400 Spring-default; see 3G
  deliberate exclusion of 400 normalisation).

### 2.2 Validation → Policy → WriteGate → Handler

Add:
1. Bean Validation → `AddPatientIdentifierValidator` (§3).
2. `AddPatientIdentifierPolicy` — requires `PATIENT_UPDATE`
   (reuse from 4A.1 role map; OWNER/ADMIN only).
3. `WriteGate.apply` opens tx; `PhiRlsTxHook.beforeExecute`
   sets both GUCs.
4. `AddPatientIdentifierHandler`:
   a. Load tenant by slug.
   b. Load patient by id; 404 on missing or cross-tenant
      (identical response — no existence leak).
   c. Build `PatientIdentifierEntity`; `saveAndFlush`.
   d. V17 RLS WITH CHECK validates OWNER/ADMIN at DB layer.
   e. UNIQUE constraint catches exact duplicates → 409
      `resource.conflict` via 3G SQLSTATE 23505 branch.
5. `AddPatientIdentifierAuditor.onSuccess` writes
   `PATIENT_IDENTIFIER_ADDED` row in same tx.

Revoke:
1. No validator (precedent from 3J.N revoke).
2. `RevokePatientIdentifierPolicy` — same `PATIENT_UPDATE`.
3. WriteGate opens tx; PhiRlsTxHook sets GUCs.
4. `RevokePatientIdentifierHandler`:
   a. Load tenant / patient (same cross-tenant defence).
   b. Load identifier by id; 404 if `patient_id` mismatch
      (ID-smuggling defence — §5 attack surface).
   c. If `valid_to != NULL`, return `changed = false` (idempotent).
   d. Else set `valid_to = NOW()`, `updated_at = NOW()`;
      `saveAndFlush`. `@Version` bumps `row_version`.
   e. V17 RLS WITH CHECK validates OWNER/ADMIN at DB layer.
5. `RevokePatientIdentifierAuditor.onSuccess` emits only
   when `changed = true`.

### 2.3 Auditor

**Success (add):**
```
action        = PATIENT_IDENTIFIER_ADDED
tenant_id     = target tenant UUID
resource_type = "clinical.patient.identifier"
resource_id   = new identifier UUID
reason        = intent:clinical.patient.identifier.add|type:<TYPE>
```
**Success (revoke):**
```
action        = PATIENT_IDENTIFIER_REVOKED
resource_id   = target identifier UUID
reason        = intent:clinical.patient.identifier.revoke|type:<TYPE>
```
**Denial (both):**
```
action        = AUTHZ_WRITE_DENIED
resource_type = "clinical.patient.identifier"
resource_id   = NULL (add) | target UUID (revoke)
reason        = intent:clinical.patient.identifier.{add|revoke}|denial:<code>
```

**`type` slug is closed-enum token only.** `issuer` and
`value` are NEVER in any audit row — `value` is PHI;
`issuer` is implementation detail beyond audit scope.

### 2.4 Response serialization

`PatientIdentifierResponse` explicit field-mapping. `type`
emits uppercase closed-enum name. `@JsonInclude(NON_NULL)`
omits unset `validFrom` / `validTo`.

---

## 3. V17 migration review

### 3.1 What V17 changes

Three policies amended on `clinical.patient_identifier`:
- `p_patient_identifier_insert` — WITH CHECK now requires
  OWNER/ADMIN in the subquery.
- `p_patient_identifier_update` — USING + WITH CHECK both require
  OWNER/ADMIN.
- `p_patient_identifier_delete` — USING requires OWNER/ADMIN.

SELECT policy unchanged — identifier read-visibility correctly
follows parent-patient visibility (any ACTIVE member).

### 3.2 Why V14 was wrong

V14 asserted in its inline comment that identifier writes
inherited the OWNER/ADMIN gate "via the subquery under
`p_patient_select`". This was FACTUALLY WRONG — `p_patient_select`
has no role gate; it checks only `status`, `tenant_id`, and
ACTIVE membership. A MEMBER caller who bypassed the application
policy (hypothetically, via a future code path that reached
`clinical.patient_identifier` directly) could INSERT/UPDATE/DELETE
at the RLS layer.

V17 closes this silently-open gap. The app-layer policy
`AddPatientIdentifierPolicy` / `RevokePatientIdentifierPolicy`
remains the primary guard; V17 is belt-and-braces.

### 3.3 Threat model

- **MEMBER role escalation via direct SQL** — prevented by
  V17 role gate. Test matrix:
  `V17 — MEMBER dave CANNOT INSERT/UPDATE/DELETE a patient_identifier`
  (3 cases proving RLS refusal).
- **Cross-tenant identifier smuggling** — identifier
  visibility delegates to parent; V14 tenant scoping on
  `clinical.patient` transitively scopes identifiers. No
  change in V17.
- **ID-smuggling via wrong URL** — handler checks
  `identifier.patient_id == patient.id`. Covered by
  `RevokePatientIdentifierIntegrationTest.identifier belongs
  to different patient — 404`.
- **Revoked-row tampering** — RLS USING applies to UPDATE
  and DELETE. A revoked identifier can still be read (SELECT
  policy unchanged), but mutations from MEMBER role are
  refused at the DB.

### 3.4 Rollback safety

V17 only amends policies via DROP/CREATE. No data change.
Fully reversible by re-applying V14's original policies. In
practice, forward-only once shipped.

---

## 4. Log-emission review

### 4.1 Zero Logger sites in 4A.3 code

`grep 'log\.'` against `com.medcore.clinical.patient.write.*`
4A.3 files — zero hits. Matches 4A.2 discipline (clinical
code emits no Logger calls; all logging is framework-level).

### 4.2 Coverage

`PatientIdentifierLogPhiLeakageTest` asserts that neither the
identifier's `value` nor `issuer` appear in captured stdout
after a POST + DELETE cycle. Synthetic tokens are
UUID-based + prefixed (`LIC-<uuid>-DistinctiveAlpha`) so any
occurrence is definitively a leak.

### 4.3 Allow-list (what MAY appear)

- `request_id` — correlation UUID.
- `user_id`, `tenant_id` — MDC keys.
- Identifier `id` — tenant-scoped opaque UUID.
- Patient `id` — same.
- `type` closed-enum token (already in audit reason slug).
- Action + closed-enum reason slugs.

### 4.4 Deny-list (what MUST NOT appear)

- `value` (for any identifier type).
- `issuer`.
- Bearer token.
- Patient PHI (names, DOB, demographics — already enforced
  by 4A.2 `PatientLogPhiLeakageTest`; 4A.3 adds no new leak
  vector here).

---

## 5. Attack-surface considerations

### 5.1 MEMBER role bypass via direct repository call

**Prevented at 3 layers:**
- AuthzPolicy refuses at app layer.
- V17 RLS WITH CHECK refuses at DB layer.
- ArchUnit Rule 12 forbids repository access outside sanctioned
  layers (existing since 3I.1).

Three independent gates. Any one alone is sufficient.

### 5.2 Identifier value forgery via direct JPA

**Prevented.** The entity flows through `saveAndFlush` inside
the WriteGate tx. No other path to the repository exists.
Rule 12 catches architectural deviation at CI.

### 5.3 Cross-patient identifier lookup

**Prevented.** Handler compares `identifier.patient_id` to the
URL-path `patientId`. Mismatch → 404 (same as unknown id).
Tested by `RevokePatientIdentifierIntegrationTest.identifier
belongs to different patient — 404`.

### 5.4 Duplicate-warning enumeration (identifier version)

**Not applicable.** Unlike 4A.2's patient create (phonetic dup
warning has a disclosure surface), identifier uniqueness is
exact-tuple. A duplicate POST returns 409 `resource.conflict`
with NO `details` payload. Zero information disclosure beyond
"that tuple already exists in this tenant" — which is exactly
what the caller asserted by making the request.

### 5.5 Replay of revoke against an already-revoked identifier

**Handled as idempotent no-op.** Handler detects `valid_to
!= NULL` and returns without mutation. 204 response; no
second audit row. Tested by
`RevokePatientIdentifierIntegrationTest.idempotent DELETE on
already-revoked — 204, NO additional audit row`.

### 5.6 Re-add of revoked identifier verbatim

**Blocked by UNIQUE constraint — carry-forward tracked.**
The UNIQUE index on `(patient_id, type, issuer, value)`
counts revoked rows. Caller cannot re-add after revoke
(returns 409). In practice extremely rare — driver's license
numbers don't recycle per-patient. Carry-forward: amend to
`WHERE valid_to IS NULL` partial unique index if a pilot
workflow demands it.

**Visibility:** called out inline in:
- `V17` migration KDoc
- `AddPatientIdentifierCommand` KDoc
- `AddPatientIdentifierHandler` KDoc
- `RevokePatientIdentifierCommand` KDoc
- This review §9 carry-forward ledger
- The eventual commit message

### 5.7 JSON injection / deserialization

Request body binds to `AddPatientIdentifierRequest` — a
simple value DTO, no polymorphic fields. Same as 4A.2.

### 5.8 Audit-reason slug tampering

**Prevented.** The `type` token is `result.type` (from the
post-save snapshot) — a closed-enum value. Not user-supplied.
No injection surface.

### 5.9 Log-leakage via exception message

**Prevented.** Handlers throw `EntityNotFoundException`
(→ 3G 404) with generic messages. No identifier value in any
exception. `WriteConflictException` from UNIQUE violation
emits fixed code, no details. 3G's `onUncaught` logs stack
trace but PG error messages for UNIQUE violations include
the TUPLE value at DETAIL level — which under default driver
config does not surface to Spring's `SQLException.getMessage()`.
Same residual as 4A.2 §2.4; tracked in post-4A.2 audit C-2.

### 5.10 Concurrency between add and revoke

**Benign.** Concurrent add + revoke on the same tuple: add
INSERT conflicts or succeeds (UNIQUE); revoke UPDATE
optimistic-locks via `@Version`. Either ordering produces a
consistent state.

### 5.11 Full-tuple enumeration via dup 409

**Not a real attack.** 409 on UNIQUE means "this exact
`(type, issuer, value)` tuple exists on this patient." The
caller already submitted the tuple — they have it. No
disclosure. Audit does not log failed adds (the exception
path doesn't auditing — 3G handles it as a generic 409).

---

## 6. Framework additions

### 6.1 No new error codes

UNIQUE → existing 409 `resource.conflict`. Not-found →
existing 404. No 428 (no PATCH).

### 6.2 Two new AuditAction entries

`PATIENT_IDENTIFIER_ADDED`, `PATIENT_IDENTIFIER_REVOKED`.
Both with NORMATIVE shape KDoc. Registry discipline
(ADR-005 §2.3): enum entry + auditor emitting + test +
review-pack callout (this doc).

### 6.3 Pattern v1.0 held

No pattern template amendments forced by 4A.3. Three
template v1.1 candidates surfaced (§10 below) — none
REQUIRED for 4A.3 correctness.

---

## 7. Test coverage summary

| Suite | Tests | Purpose |
| ---- | ---- | ---- |
| `AddPatientIdentifierValidatorTest` | 10 | Every closed-enum code: blank / format / too_long / control_chars / valid_range |
| `AddPatientIdentifierIntegrationTest` | 8 | HTTP→DB happy + ADMIN + MEMBER denial + cross-tenant + duplicate → 409 + missing-issuer 422 + unknown-type 422 |
| `RevokePatientIdentifierIntegrationTest` | 5 | Happy path + idempotent + cross-tenant + cross-patient + MEMBER denial |
| `PatientIdentifierLogPhiLeakageTest` | 1 | value + issuer never appear in logs |
| `PatientSchemaRlsTest` (+4 new) | 14 (was 10) | V17 role gate: OWNER ok + MEMBER refused on INSERT/UPDATE/DELETE |

**Total 4A.3 new tests:** 28 (of which +4 are extensions to
existing RLS suite).
**Suite total:** 390 → 418.

---

## 8. Carry-forwards closed by 4A.3

- **"Identifier management is a separate slice"** (4A.2 PHI
  review) — CLOSED.
- **V14 identifier RLS role-gate gap** (surfaced during 4A.3
  pattern-validation) — CLOSED via V17. Footer addendum
  added to `phi-exposure-review-4a-2.md` noting this
  retrospectively.
- **"First real reuse of clinical-write-pattern v1.0"** —
  CLOSED. Pattern held.

---

## 9. Carry-forwards opened by 4A.3

| # | Item | Rationale | Target close |
| ---- | ---- | ---- | ---- |
| **CF-4A3-1** | Partial UNIQUE index on `clinical.patient_identifier` — `WHERE valid_to IS NULL` — to allow re-add after revoke | Extremely rare in practice (license numbers don't recycle per patient); current UNIQUE is conservative-correct | When a pilot clinic's workflow demands re-add-after-revoke |
| **CF-4A3-2** | UPDATE identifier command (value/issuer change without revoke-readd) | 4A.3 ships POST + DELETE only; update is a distinct shape (value change is an identity change with compliance implications) | 4A.3.1 or later |
| **CF-4A3-3** | `clinical-write-pattern.md` v1.1 amendments (3 items from §10 below) | Template drift surfaced but not blocking | Next governance slice |

---

## 10. Pattern v1.0 amendments proposed (template v1.1)

1. **§10 checklist addition:** "Does this feature need a new
   authority, or does an existing one fit?" — force an
   explicit decision rather than leaving it as a silent
   judgement call.
2. **§7.2 scope clarification:** `If-Match` requirement is
   for **PHI PATCH**, not all mutations. DELETE on
   satellites does not require `If-Match` (lifecycle
   transitions are idempotent; precedent from 3J.N + 4A.3).
3. **§1.1 RLS option:** explicitly permit "delegates to
   parent via EXISTS" as an INCIDENTAL RLS style for
   satellites, alongside direct both-GUCs. Requires that
   the delegation chain terminates in a parent policy with
   role gating if writes need role scope.

All three land as `clinical-write-pattern.md` v1.1 in this
slice's governance commit.

---

## 11. Conclusion

Phase 4A.3 successfully exercises the clinical-write-pattern
v1.0 as a forcing function. The pattern held: no redesign
required, no hidden coupling surfaced, no new architectural
concepts needed. The process DID surface a real gap in V14
(identifier RLS missing role gate) — which is exactly what a
pattern-validation exercise is for. V17 closes the gap.

**Three v1.1 template amendments** land in this slice's
governance commit. Four new suites + 4 new RLS cases prove
defence in depth. Audit discipline held: `value` and
`issuer` never appear in logs, MDC, or reason slugs.

**Audit sign-off: GREEN.** Proceed to 4A.4 (FHIR mapping) or
4A.5 (read + read-audit) per roadmap.

---

*Generated during Phase 4A.3 execution (2026-04-23).
Amendments to this review follow the same ADR discipline as
the pattern template.*
