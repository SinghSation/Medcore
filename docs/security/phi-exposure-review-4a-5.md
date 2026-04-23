# PHI Exposure Review — Phase 4A.5 (FHIR Patient Mapping)

**Slice:** Phase 4A.5 — first FHIR wire-shape endpoint in
Medcore. Ships `GET /fhir/r4/Patient/{patientId}` returning a
**minimal FHIR R4 Patient resource, US Core–influenced**
(explicitly NOT a US Core v6.x profile-conformant
implementation).

Last backend slice before the vertical-slice (frontend + first
clinical workflow) pivot. After 4A.5, no new backend slice
starts until a UI plan is approved.

**Reviewer:** Repository owner (solo).
**Date:** 2026-04-23.
**Scope:** all 4A.5 code + no migrations + tests + governance
docs. Adds one controller (`PatientFhirController`), one
typed DTO hierarchy (`FhirPatient` + friends), one mapper
(`PatientFhirMapper`), and extends 7 filter/security surfaces
to cover the `/fhir/**` namespace. Reuses ALL 4A.4 read-path
infrastructure (ReadGate, GetPatientCommand, GetPatientHandler,
GetPatientAuditor, CLINICAL_PATIENT_ACCESSED).

**Risk determination:** **Low.** Shape-only change — no new
PHI surfaces, no new audit actions, no new authorities, no new
migrations. The one genuine attack-surface concern (wire-shape
exception from the canonical-envelope rule) is tested directly.

---

## 0. Scope discipline (what 4A.5 deliberately is and is NOT)

### Is

- One endpoint: `GET /fhir/r4/Patient/{patientId}`
- Bare FHIR R4 Patient resource in response body
- `X-Request-Id` header as correlation substitute for the
  canonical-envelope `requestId` field
- Reuse of 4A.4 read-path machinery (zero new gate/command/
  handler/audit action/authority)

### Is NOT

- US Core Patient v6.x **profile-conformant** implementation
  (explicit wording rule — see §6)
- FHIR search (`GET /fhir/r4/Patient?...`)
- FHIR Bundle wrapping
- Other FHIR resources (Observation, Encounter, etc.)
- CapabilityStatement, SMART launch, `.well-known` endpoints
- HAPI FHIR library integration
- Satellite identifier rendering (DRIVERS_LICENSE,
  INSURANCE_MEMBER, MRN_EXTERNAL, OTHER — carry-forward)

---

## 1. What's mapped

FHIR Patient fields emitted (by `PatientFhirMapper`):

| FHIR field | Medcore source | Notes |
| ---- | ---- | ---- |
| `resourceType` | literal `"Patient"` | |
| `id` | patient UUID | |
| `meta.versionId` | `row_version` | |
| `meta.lastUpdated` | `updated_at` | |
| `identifier[0]` | Medcore MRN | `use: usual`, system: `urn:medcore:tenant:{tenant-uuid}:mrn`, type coding: HL7 v2-0203 "MR" |
| `active` | `status == ACTIVE` | MERGED_AWAY + DELETED → false |
| `name[0]` | family + given (+middle) + prefix + suffix | `use: official` |
| `name[1]` | `preferredName` (when non-null) | `use: usual`, `text` only |
| `gender` | `administrative_sex` | FHIR wire value already stored natively |
| `birthDate` | `birth_date` | |
| US Core birthsex extension | `sex_assigned_at_birth` (when non-null) | `valueCode`: M / F / UNK |
| US Core gender-identity extension | `gender_identity_code` (when non-null) | `valueString` (source column is free-string today; future upgrade to `valueCoding` when SNOMED input lands) |
| `communication[0].language` | `preferred_language` + BCP-47 system | `preferred: true` |

### Not mapped (deliberate)

- `identifier[1..n]` — satellite `patient_identifier` rows
- `telecom` — phone/email not stored
- `address` — not stored (4A.3.1 territory)
- `contact` — next-of-kin not stored
- US Core race / ethnicity extensions — 4A.1 design deferred
- `maritalStatus`, `multipleBirth*`, `photo`, `link` — not stored

---

## 2. Data-flow review

### 2.1 HTTP entry

`GET /fhir/r4/Patient/{patientId}` lands on
`PatientFhirController.getPatient`. Filter chain:

1. `RequestIdFilter` (covers all paths)
2. Spring Security JWT filter (extended to `/fhir/**` via
   `securityMatcher("/api/**", "/fhir/**")`)
3. `MdcUserIdFilter` (URL pattern now `/api/*` + `/fhir/*`)
4. `TenantContextFilter` (same extension) — resolves
   `X-Medcore-Tenant` header
5. `PhiRequestContextFilter` (same extension) — populates
   `PhiRequestContextHolder` from principal + tenant

All 5 filters were retrofitted in 4A.5: 3 one-line
`shouldNotFilter` + 3 `addUrlPatterns("/api/*", "/fhir/*")` +
1 `securityMatcher` extension = **7 filter-surface edits
total**. All documented inline with 4A.5 reference.

### 2.2 Controller → ReadGate → handler → mapper

- `getPatientGate.apply(command, context) { handler.handle(...) }`
  — identical to 4A.4
- Inside the gate's tx, `PhiRlsTxHook` sets both GUCs,
  `GetPatientHandler` loads tenant + patient, returns
  `PatientSnapshot`
- `GetPatientAuditor.onSuccess` emits
  `CLINICAL_PATIENT_ACCESSED` in-tx (same action as 4A.4
  native path)
- Controller calls `patientFhirMapper.toFhir(snapshot)` →
  `FhirPatient`
- Response: bare FHIR body + `ETag: "<rowVersion>"` header +
  `X-Request-Id: <uuid>` header

### 2.3 Audit emission

Same discipline as 4A.4:
- **200 with body** → one `CLINICAL_PATIENT_ACCESSED` row
- **404 (unknown / cross-tenant / DELETED)** → NO audit row
- **422 / 403 (filter-level)** → NO audit row (filter emits
  its own `tenancy.membership.denied`)
- **500** → NO audit row

Reason slug unchanged:
`intent:clinical.patient.access` — PHI disclosure is PHI
disclosure regardless of wire shape. A forensic query "who
accessed patient X?" returns both native and FHIR reads
uniformly.

---

## 3. Wording discipline (explicit NORMATIVE call-out)

Any text in this codebase, commit messages, docs, or reviews
that describes this slice as "US Core Patient v6.x JSON" or
"US Core Patient v6.x conformant" is **incorrect**. The
implementation in 4A.5 is:

> **minimal FHIR R4 Patient mapping — US Core–influenced
> shape**

The difference matters. US Core v6.x Patient requires:
- Race + ethnicity extensions (we don't store them)
- Address array with specific fields (we don't store them)
- Multiple identifiers including SSN where legal (we don't)
- Telecom (we don't)
- Communication language with required coding (we have minimal)

Claiming "US Core v6.x" when the above are absent would
misrepresent our conformance surface to future readers. This
wording discipline is enforced by:
- `FhirPatient.kt` KDoc first paragraph
- `PatientFhirMapper.kt` KDoc "Scope — explicit" section
- `PatientFhirController.kt` KDoc scope bar
- This review §0 and §3
- The 4A.5 commit message

If a future slice adds race/ethnicity/telecom/address and
the reviewer determines the resulting output meets US Core
v6.x Patient profile requirements, the wording can be
upgraded — **in the same commit** that delivers the
conformance. Not before.

---

## 4. Attack-surface considerations

### 4.1 PHI in logs / MDC / spans

**Prevented.** `PatientFhirLogPhiLeakageTest` fires a FHIR GET
with distinctive synthetic tokens (random-suffixed names +
BCP-47 language tag). Captured stdout is grep-asserted
against each token + the bearer. No clinical log sites added
in 4A.5 code.

### 4.2 PHI disclosure without audit

**Prevented.** Audit emission is in-tx (ReadGate's
`TransactionTemplate`). Failure → tx rollback → response
body never serialised → 500 via 3G `onUncaught`. Same
mechanism proven by `ReadGateTest` in 4A.4. 4A.5 adds no
new code path that could disclose PHI without audit.

### 4.3 Cross-tenant id probing

**Prevented.** Handler's `patient.tenantId != tenant.id`
check fires before any disclosure; returns
`EntityNotFoundException` → 404 (identical to unknown id).
V14 RLS blocks at DB layer too. Tested by
`GetPatientFhirIntegrationTest.FHIR GET cross-tenant
patientId — 404 with NO access audit row`.

### 4.4 Wire-shape exception — `ApiResponse` wrapper leakage

**Prevented + directly tested.** The canonical-envelope rule
in `clinical-write-pattern.md` §12.6 says all responses use
`ApiResponse<T>`. 4A.5 takes the narrow exception: FHIR
endpoints return bare FHIR resources. A programming bug could
accidentally leak `{data: {...}, requestId: "..."}` if a
developer later copy-pasted wrapper code from the native
controller.

**Explicit test:**
```kotlin
assertThat(body)
    .describedAs("FHIR body must NOT contain ApiResponse wrapper fields")
    .doesNotContainKey("data")
    .doesNotContainKey("requestId")
```
in `GetPatientFhirIntegrationTest` — enforces the exception
scope at CI time.

### 4.5 Identifier value disclosure via FHIR system URI

**Not a concern.** The system URI is
`urn:medcore:tenant:{tenant-uuid}:mrn`. This discloses the
tenant UUID (already visible to callers who have a valid
`X-Medcore-Tenant` header for that tenant) and a fixed
literal "mrn". No PHI.

### 4.6 FHIR extension value disclosure

**Constrained.** Two extensions emitted:
- `us-core-birthsex` → `valueCode` ∈ {M, F, UNK} — closed set
- `us-core-genderIdentity` → `valueString` with the raw
  `gender_identity_code` column value. Today this column
  accepts arbitrary string input (e.g., "gender-fluid", a
  SNOMED code, a free-form descriptor). **Anything the
  patient's caller entered ends up on the wire** for readers
  who have `PATIENT_READ`.

This is the same surface as the native GET (`administrativeSex`
+ `genderIdentityCode` are visible there too), so FHIR doesn't
expand the disclosure vector. But it's worth noting as PHI
review diligence: a future slice that validates / constrains
the gender_identity_code input format (e.g., require SNOMED
coding) will also constrain this FHIR field automatically.

### 4.7 Filter-chain reordering risk

**Controlled.** The filter registration extensions add a
second URL pattern to each existing registration; they do
NOT change the `order` property or the registration of any
other filter. The ordering invariant (TenantContextFilter
+10 → PhiRequestContextFilter +20) holds for `/fhir/**`
identically to `/api/**`.

### 4.8 Authentication-required coverage

**Controlled.** `securityMatcher("/api/**", "/fhir/**")`
applies the same `.anyRequest().authenticated()` rule to
both. A hypothetical unauthenticated request to
`/fhir/r4/Patient/*` returns 401 via the same auditing
entry point as `/api/**`. Tested by
`GetPatientFhirIntegrationTest.FHIR GET without bearer
returns 401`.

### 4.9 MDC correlation gap on FHIR path

**Prevented.** `MdcUserIdFilter` now covers `/fhir/**`; MDC
`user_id` is populated for FHIR requests identically to
`/api/**` requests. Structured logs remain correlatable
across the two namespaces.

### 4.10 URL-path-based enumeration

**Not applicable.** Both `/api/v1/tenants/{slug}/patients/{id}`
and `/fhir/r4/Patient/{id}` require authentication + tenant
resolution + RLS-gated load. A caller without tenant
membership cannot enumerate by trying different `patientId`s
— they get 404 with no audit row regardless of whether the
id exists in another tenant.

---

## 5. Test coverage summary

| Suite | Tests | Purpose |
| ---- | ---- | ---- |
| `PatientFhirMapperTest` | 13 | Unit coverage of every mapped field, including US Core extensions conditionally emitted when source columns set |
| `GetPatientFhirIntegrationTest` | 7 | HTTP→DB FHIR path: 401, 422/403 missing tenant, bare-body success, tenant-scoped URN identifier, MEMBER read, 404 unknown, 404 cross-tenant |
| `PatientFhirLogPhiLeakageTest` | 1 | PHI tokens never appear in captured stdout on FHIR GET |

**Total 4A.5 new tests:** 21.
**Suite total:** 434 → 455.

---

## 6. Carry-forwards

### Closed by 4A.5

- **GET /fhir/r4/Patient/{id}** (4A.2 carry-forward,
  reordered from 4A.4 to 4A.5 during 4A.4 DoD
  reconciliation) — closed.

### Opened by 4A.5

| # | Item | Rationale | Target close |
| ---- | ---- | ---- | ---- |
| CF-4A5-1 | Canonical identifier system URI (OID or DNS-based) replacing `urn:medcore:tenant:{uuid}:mrn` | URN form is interim; real FHIR integrations may prefer OID or DNS forms | Slice when Medcore commits to a public identity-system namespace |
| CF-4A5-2 | Satellite identifier rendering in FHIR (DRIVERS_LICENSE, INSURANCE_MEMBER, etc.) | 4A.5 ships MRN only; satellite rendering is additive but has PHI disclosure consequences | Slice with its own PHI-exposure review |
| CF-4A5-3 | Gender-identity `valueCoding` upgrade (from `valueString`) | Source column is free-string today; SNOMED-coded input is a future data-quality slice | When gender-identity input validation lands |
| CF-4A5-4 | US Core Patient v6.x profile conformance | Requires race/ethnicity/telecom/address mapping — out of scope for 4A.5 | Each missing field is its own slice |
| CF-4A5-5 | FHIR search endpoint (`GET /fhir/r4/Patient?...`) | Search has pagination + authz + audit semantics beyond single-resource read | When an external integration demands it |
| CF-4A5-6 | FHIR `Bundle` / `CapabilityStatement` / SMART launch | Full FHIR server surface — dedicated slice | When interop integration becomes a concrete goal |

---

## 7. Conclusion

Phase 4A.5 shipped Medcore's first FHIR wire-shape endpoint
with full discipline: minimal scope, zero new attack surface,
one wire-shape exception (canonical envelope → bare FHIR
resource) tested directly, explicit wording to prevent
over-claiming US Core conformance.

The filter-chain extension pattern (3 filter registrations +
1 security config + 3 `shouldNotFilter` checks) is the
reference pattern for any future namespace expansion (e.g.,
`/smart/**`, `/fhir/v2/**`, `/oauth/**`).

**Audit sign-off: GREEN.**

**Engineering commitment (repeated in the 4A.5 commit body):**
after this commit, no new backend slice starts until a
vertical-slice UI plan is approved and signed off. This
forcing function pivots Medcore from "backend architecture
completion" to "user-facing product execution."

---

*Generated during Phase 4A.5 execution (2026-04-23).*
