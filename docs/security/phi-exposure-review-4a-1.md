# PHI Exposure Review — Phase 4A.1 (clinical.patient schema)

**Slice:** Phase 4A.1 — `V14__clinical_patient_schema.sql` (first
PHI-bearing table in Medcore), `clinical.patient_identifier`
satellite, Kotlin entities + repositories, FHIR-aligned
demographic columns, `PATIENT_READ` / `PATIENT_CREATE` /
`PATIENT_UPDATE` authorities added to `MedcoreAuthority`, role
map extension (OWNER + ADMIN gain all three; MEMBER gains
`PATIENT_READ` only), RLS policies keying on BOTH
`app.current_tenant_id` AND `app.current_user_id`, fail-closed
GUC discipline, soft-delete hiding at SELECT layer, identifier
transitivity via parent, `MIN_EXPECTED_INSTALLED_RANK` bumped
14.

**Reviewer:** Repository owner (solo).
**Date:** 2026-04-23.
**Scope:** All Phase 4A.1 code + V14 migration + role-map
changes + test coverage. **No HTTP endpoints, no service class,
no handler, no auditor in this slice** — the patient read/write
surface lands in Phase 4A.2.

**Risk determination:** Low. This is the first slice that lands
a PHI-bearing SQL surface, but there is zero application-level
access to it: no service, no endpoint, no repository consumer.
Every RLS policy is fail-closed and keyed on both tenant and
caller GUCs; every CHECK constraint is database-enforced; the
ArchUnit + WriteGate perimeter established in 3I.1 + 3J.1 +
4A.0 constrains the 4A.2 handler that will eventually consume
this schema.

---

## 1. What this slice handles

Phase 4A.1 establishes the durable shape of Medcore's first
PHI-bearing surface — the `clinical.patient` table — without
shipping any way to reach it from an HTTP caller.

New data surfaces:

- **`clinical.patient`** rows. PHI columns:
  - `name_given`, `name_family`, `name_middle`, `name_suffix`,
    `name_prefix` (HumanName parts — FHIR
    `Patient.name[use='official']`).
  - `preferred_name` (FHIR `Patient.name[use='usual']`).
  - `birth_date` (FHIR `Patient.birthDate`).
  - `administrative_sex` (TEXT + CHECK on the FHIR wire set
    `{male, female, other, unknown}` — FHIR `Patient.gender`).
  - `sex_assigned_at_birth` (TEXT + CHECK on `{M, F, UNK}` —
    US Core extension).
  - `gender_identity_code` (TEXT, nullable — US Core extension;
    SNOMED-coded when populated, 4A.2+ concern).
  - `preferred_language` (TEXT, nullable — FHIR
    `Patient.communication.language`).
  - Non-PHI lifecycle + audit columns: `mrn`, `mrn_source`,
    `status`, `merged_into_id`, `merged_at`, `merged_by`,
    `created_at`, `updated_at`, `created_by`, `updated_by`,
    `row_version`.
- **`clinical.patient_identifier`** rows. PHI-adjacent columns:
  - `type` (closed enum: `MRN_EXTERNAL` / `DRIVERS_LICENSE` /
    `INSURANCE_MEMBER` / `OTHER`).
  - `issuer` (issuing authority label — e.g., `CA` state for a
    license, `Aetna` for a payer).
  - `value` (the identifier string itself — the driver's license
    number, insurance member number, external MRN). **PHI per
    45 CFR §164.514(b)(2) — identifier-style data element.**
- **`MedcoreAuthority`** closed-enum gains three PHI-domain
  authorities (`PATIENT_READ`, `PATIENT_CREATE`, `PATIENT_UPDATE`)
  — wire strings `MEDCORE_PATIENT_READ` / `..._CREATE` /
  `..._UPDATE`. Workforce-facing authority grants, not PHI.
- **`MembershipRoleAuthorities`** role map gains nine entries
  (3 authorities × 3 roles). Tenancy-role metadata, not PHI.

What 4A.1 does NOT ship (deliberately):

- No `PatientService`, no `CreatePatientCommand`, no
  `CreatePatientHandler`, no `PatientController`.
- No FHIR-shape read endpoint (Phase 4A.4).
- No read auditing (Phase 4A.5 — `CLINICAL_PATIENT_ACCESSED`
  `AuditAction` not yet added).
- No SSN column, no SSN in `PatientIdentifierType` (deliberate
  per design-pack §9.3; dedicated slice with compliance review
  when a pilot customer demands it).
- No `race` or `ethnicity` columns (US Core OMB categories
  deferred to a later 4A slice to avoid scope creep).
- No `PatientRepository` caller: the interface extends
  `JpaRepository<PatientEntity, UUID>` with zero additional
  query methods, and no application code references it outside
  `PatientEntityMappingTest` (admin-path) and
  `PatientSchemaRlsTest` (raw-JDBC path). Enforcement is
  structural: any 4A.2 consumer must add the interface to its
  own constructor, which makes adoption visible in review.

---

## 2. Data-flow review

### 2.1 HTTP → Controller

**None.** 4A.1 adds no controller and no endpoint. The patient
table is a dormant surface at this commit — Spring routes no
request to it. This cuts out every HTTP-layer PHI concern
(request bodies, query params, response shapes, error messages,
log MDC keys).

### 2.2 Service / Handler / WriteGate

**None.** No service class in `com.medcore.clinical.patient.*`.
No WriteGate bean wiring. No `AuthzPolicy` implementation. The
4A.2 slice will add `CreatePatientHandler` and
`UpdatePatientDemographicsHandler` through the 3J.1 WriteGate
perimeter — the pattern is already landed, mechanical to
adopt.

### 2.3 Repository → SQL

Two Spring Data interfaces (`PatientRepository`,
`PatientIdentifierRepository`) are declared, but both are
**unused in application code**. The only callers are the two
tests in this slice:

- `PatientSchemaRlsTest` — uses raw `JdbcTemplate` against the
  qualified `appDataSource` + `adminDataSource` to exercise
  RLS. Does not go through Spring Data.
- `PatientEntityMappingTest` — uses `PatientRepository` +
  `PatientIdentifierRepository` via `saveAndFlush` to verify
  JPA column mapping. The test seeds a fresh tenant +
  membership via `adminDataSource` and sets both RLS GUCs via
  `TenancySessionContext.apply()` before invoking the
  repository — mirrors the runtime `PhiRlsTxHook` pattern
  exactly.

No application code reaches the repository. The 4A.2 slice will
wire it through a `@Service` class that ArchUnit Rule 13
requires to depend on `PhiSessionContext` (the dev-time
artifact of calling `applyFromRequest()` at the top of each
`@Transactional` method).

### 2.4 RLS policy — the only runtime gate on this table

V14 installs **four policies on `clinical.patient`** and **four
policies on `clinical.patient_identifier`**. Every policy keys
on BOTH `app.current_tenant_id` AND `app.current_user_id` via
`NULLIF(current_setting(..., true), '')::uuid`. Missing either
GUC causes a NULL comparison, which evaluates UNKNOWN, which
filters the row out — fail-closed by construction. The tests
`missing tenant GUC returns zero rows` and
`missing user GUC returns zero rows` in `PatientSchemaRlsTest`
prove both halves of the invariant.

**`clinical.patient` SELECT policy** (`p_patient_select`) adds
two further gates on top of the both-GUCs requirement:

1. `status != 'DELETED'` — soft-deleted rows are invisible to
   the application via RLS. Forensic access (if ever required)
   must go through a future SECURITY DEFINER helper; the
   normal read path cannot surface these rows. The test
   `DELETED patient is invisible to owner via SELECT` proves
   this.
2. `EXISTS (SELECT 1 FROM tenancy.tenant_membership ...)` —
   the caller must be an ACTIVE member of the patient's
   tenant. SUSPENDED memberships reveal nothing (tested by
   `carol with SUSPENDED membership sees zero patients`).
   Non-members see nothing (tested by `eve with no memberships
   sees zero patients anywhere`).

**`clinical.patient` INSERT / UPDATE / DELETE policies** add a
role gate on top: the caller's ACTIVE membership must carry
`role IN ('OWNER', 'ADMIN')`. Tested by
`OWNER alice CAN INSERT` (success) and
`MEMBER dave CANNOT INSERT — WITH CHECK violation` (RLS
refusal). Cross-tenant UPDATE silently matches zero rows
(`alice CANNOT UPDATE a tenant B patient`).

**`clinical.patient_identifier` policies** delegate parent-row
visibility via `EXISTS (SELECT 1 FROM clinical.patient p WHERE
p.id = patient_identifier.patient_id)`. Because the subquery's
SELECT is itself subject to `p_patient_select`, identifier
visibility inherits the parent's RLS semantics: DELETED
patients' identifiers are hidden, cross-tenant identifiers are
invisible, SUSPENDED members see no identifiers. Tested by
`patient_identifier visibility follows parent patient row`.

### 2.5 Recursion analysis (RLS subquery termination)

The policies contain `EXISTS (SELECT 1 FROM
tenancy.tenant_membership ...)` subqueries. Those subqueries
trigger RLS policies on `tenancy.tenant_membership`:

- V8 `p_membership_select_own` — `USING user_id = GUC`.
  Non-self-referential on `clinical.patient`.
- V13 `p_membership_select_by_admin_or_owner` — calls the
  SECURITY DEFINER function `tenancy.caller_is_tenant_admin`,
  which is owned by `medcore_rls_helper` (NOLOGIN, BYPASSRLS).
  Non-recursive by construction (BYPASSRLS short-circuits
  policy evaluation inside the function).

The subquery terminates in one step. Its own `WHERE
tm.user_id = caller` clause narrows to the caller's own rows,
so V13's broader admin-visibility policy doesn't affect the
clinical-row decision. Documented inline in V14 §Recursion
analysis.

### 2.6 CHECK constraint discipline

V14 installs six CHECK constraints on `clinical.patient` +
one on `clinical.patient_identifier`:

1. `ck_clinical_patient_administrative_sex` — wire values
   `{male, female, other, unknown}` only. Wrong case (`MALE`)
   is rejected at INSERT time. Tested by
   `CHECK constraint rejects invalid administrative_sex value`.
2. `ck_clinical_patient_status` — `{ACTIVE, MERGED_AWAY,
   DELETED}` only. Tested.
3. `ck_clinical_patient_mrn_source` — `{GENERATED, IMPORTED}`
   only.
4. `ck_clinical_patient_sex_assigned_at_birth` — nullable OR
   `{M, F, UNK}`.
5. `ck_clinical_patient_merged_fields_coherent` — the
   five-field invariant that `status = 'MERGED_AWAY'` iff
   `(merged_into_id, merged_at, merged_by)` are all non-null.
   Prevents incoherent merge state from ever living on disk
   even if a future handler has a bug. Tested by
   `CHECK ck_clinical_patient_merged_fields_coherent rejects
   incoherent merge fields`.
6. `ck_clinical_patient_identifier_type` — closed-enum
   (`MRN_EXTERNAL | DRIVERS_LICENSE | INSURANCE_MEMBER |
   OTHER`). SSN is deliberately absent; adding it is an
   additive enum update with its own PHI review.

UNIQUE constraint: `uq_clinical_patient_tenant_mrn` on
`(tenant_id, mrn)` — prevents duplicate Medcore-minted MRNs
within a tenant. Tested by `UNIQUE (tenant_id, mrn) rejects
duplicates`.

### 2.7 Indexing choices (duplicate-detection-aware)

- `ix_clinical_patient_tenant` — primary tenant-scoped lookup.
- `ix_clinical_patient_tenant_dob_family_given` on
  `(tenant_id, birth_date, lower(name_family), lower(name_given))`
  — exact-match duplicate candidate lookup. Anticipates the
  4A.2 duplicate-warning path.
- `ix_clinical_patient_tenant_soundex_family` on
  `(tenant_id, soundex(name_family))` — phonetic
  duplicate-warning index. Uses the Postgres
  `fuzzystrmatch` contrib extension (installed in V14).
  No PHI leaves the database to compute soundex.
- `ix_clinical_patient_identifier_patient` — satellite
  parent lookup.

None of the indexes bleed data outside the database. All
index-key computations are Postgres built-ins running
in-process.

### 2.8 Audit surface

Unchanged in 4A.1. `audit.audit_event` has no new columns, no
new `AuditAction` entry. Read auditing of patient rows
(`CLINICAL_PATIENT_ACCESSED`) lands in Phase 4A.5; write
auditing of patient mutations lands in 4A.2 when
`CreatePatientAuditor` wires through the 3J.1 WriteGate
perimeter.

---

## 3. V14 migration — review specifics

### 3.1 Why V14 exists

Phase 4A.1 is the schema landing for Medcore's first PHI
surface. The migration must be reversible at 4A.1 scale (no
clinical data yet) but is the last "reversible" clinical
migration — once 4A.2 handlers start persisting patient rows,
ADR-001 §7 prohibits in-place table drops.

### 3.2 What V14 adds

1. `CREATE EXTENSION IF NOT EXISTS fuzzystrmatch` — the contrib
   extension ships with PostgreSQL 16 and needs no custom
   binaries. Used solely by `ix_clinical_patient_tenant_soundex_family`.
   Grants are not relevant (extension-provided functions are
   non-SECURITY-DEFINER, owned by the superuser that installed
   them).
2. `CREATE SCHEMA clinical` + `GRANT USAGE ON SCHEMA clinical
   TO medcore_app` + `GRANT USAGE, CREATE ON SCHEMA clinical
   TO medcore_migrator`. Mirrors the Phase 3A / 3B / 3C grant
   pattern for `identity`, `tenancy`, `audit`.
3. `CREATE TABLE clinical.patient` with 22 columns and the
   constraints catalogued in §2.6.
4. `CREATE TABLE clinical.patient_identifier` satellite.
5. Four indexes (two PHI-aware, two lookup-aware).
6. Eight RLS policies (four per table).
7. `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` + `FORCE ROW
   LEVEL SECURITY` on both tables — forces RLS to apply even
   to the table owner, so even a misconfigured app path that
   connects as an elevated role cannot bypass the policy set
   short of `BYPASSRLS` on the role.
8. `GRANT SELECT, INSERT, UPDATE, DELETE ON ... TO medcore_app`
   — the application role gets the full DML surface; RLS is
   the gate, not the grant.

### 3.3 Threat model

- **Application role bypass:** `medcore_app` lacks BYPASSRLS.
  `FORCE ROW LEVEL SECURITY` means even if ownership were
  somehow transferred, RLS still applies. The 3I.1 ArchUnit
  Rule 10 (`DatabaseRoleSafetyCheck` presence) + 3E runtime
  datasource discipline ensure the application never connects
  as a BYPASSRLS role in prod.
- **SECURITY DEFINER attack surface:** V14 adds NO SECURITY
  DEFINER functions. The only SECURITY DEFINER function
  reachable from clinical policies is the V13
  `tenancy.caller_is_tenant_admin`, and V14's patient
  policies do NOT call it directly — they inline the
  membership-check subquery with explicit role gate
  (`role IN ('OWNER', 'ADMIN')`). No expansion of SECURITY
  DEFINER blast radius.
- **Schema-shadowing:** N/A (no SECURITY DEFINER in V14).
- **Missing-GUC bypass:** Every policy uses
  `NULLIF(current_setting(..., true), '')::uuid` which yields
  NULL on an empty or unset GUC. NULL comparisons return
  UNKNOWN. UNKNOWN in a policy expression filters rows out.
  Tested by `missing tenant GUC returns zero rows` and
  `missing user GUC returns zero rows`.
- **Cross-tenant INSERT via crafted payload:** RLS WITH CHECK
  rejects any row whose `tenant_id` differs from the caller's
  tenant GUC. Tested indirectly via the role gate test; direct
  WITH CHECK violation emerges automatically from the INSERT
  policy's tenant-match clause.
- **MERGED_AWAY row visibility:** Deliberately preserved
  (merge-unwind workflow needs to see them). The 4A.1 design
  pack documents that MERGED_AWAY is a *post-operation*
  state, not a deletion. DELETED is the terminal
  soft-delete state and IS hidden.

### 3.4 Rollback safety

DROP TABLE `clinical.patient_identifier` + `clinical.patient`
+ DROP SCHEMA `clinical` + DROP EXTENSION `fuzzystrmatch` (if
no other consumers) is acceptable at 4A.1 scale because no
clinical data exists yet. After 4A.2 ships, drop is prohibited
(ADR-001 §7 — forward-only migrations for data-bearing
tables); any shape change at that point goes through an
additive migration with a subsequent data-preserving
re-shape.

### 3.5 Existing tests still green

The 17 existing test suites that reset `tenancy.tenant` now
also reset `clinical.patient_identifier` + `clinical.patient`
first (FK-dependency-order cleanup). No behavioural change to
any existing test — only a one-line-each DELETE-ordering
change. All tests continue to pass:
**358/358 across 66 suites**.

---

## 4. Log-emission review

No new application log sites added. No `Logger` instantiated
in 4A.1 code. The JPA entity classes + repository interfaces
are pure data holders; the V14 migration runs through Flyway
which Spring Boot auto-configures. Flyway log lines during
startup carry the migration filename + version but no PHI row
content.

`LogPhiLeakageTest` (3F.1) + `TracingPhiLeakageTest` (3F.2)
continue to pass unchanged — no new `medcore.*` MDC keys, no
new tracing-span attributes. The PHI-log-redaction discipline
remains purely preventive; no PHI ever crosses a log boundary
in this slice because there is no application-layer read of
`clinical.patient` in 4A.1.

Future 4A.2 consumers must abide by the same discipline:
`LogPhiLeakageTest` will catch anyone who introduces a patient
field to a log line. Enforcement at the build level is already
in place.

---

## 5. Attack-surface considerations

### 5.1 Forgotten `PhiSessionContext.applyFromRequest()` in 4A.2

**Prevented structurally by the fail-closed RLS design.**
If a 4A.2 handler forgets to call `applyFromRequest()` at the
top of its `@Transactional` method, both GUCs are unset, every
RLS policy fails closed, and every `PatientRepository`
operation returns zero rows (for SELECT) or `WITH CHECK`
failures (for INSERT). The symptom is "patient disappeared,"
which is annoying but loud — never a silent PHI leak.
**ArchUnit Rule 13** (`ClinicalDisciplineArchTest`) additionally
forbids a clinical service class from compiling without a
`PhiSessionContext` dependency; the rule is currently
`.allowEmptyShould(true)` because no clinical service exists
yet in 4A.1. The allowance is removed in 4A.2 (when
`PatientService` lands), closing the compile-time guard.

### 5.2 Cross-tenant probing via crafted UUIDs

**Prevented by RLS.** A caller in tenant A who somehow obtains
a tenant-B patient UUID and issues `SELECT ... WHERE id = ?`
gets zero rows. UPDATE silently matches zero rows. Identifier
queries via the satellite likewise return zero rows. No
existence leak. Tested by `alice CANNOT UPDATE a tenant B
patient — USING clause hides the row` and `eve with no
memberships sees zero patients anywhere` (across both tenants).

### 5.3 MEMBER role escalation via direct SQL

**Prevented by the role gate in WITH CHECK.** A MEMBER-role
caller invoking a raw INSERT against `clinical.patient` hits
the `role IN ('OWNER', 'ADMIN')` subquery check and is
refused. Tested by `MEMBER dave CANNOT INSERT a patient —
WITH CHECK violation`. No 4A.1 authz-layer code is involved;
this is entirely a database-layer refusal.

### 5.4 Row-level information disclosure via index build

The soundex index does phonetic matching, but the index is
consulted only inside the database by SELECT queries that are
themselves subject to RLS. No background job, no logical
replication, no extract-to-file path in 4A.1. Existing
`medcore_migrator` role is the only path that could `COPY`
data out of the table, and that role is not in the runtime
auth surface.

### 5.5 Stale schema with mismatched Flyway rank

**Prevented by `FlywayMigrationStateCheck`.**
`MIN_EXPECTED_INSTALLED_RANK` bumped 13 → 14 in 4A.1 chunk A.
If an operator launches the application binary against a DB
still at rank 13, the check refuses startup with a specific
error message. Test `schema at rank N` in
`MedcoreApiApplicationTests` (`v14_clinical_patient_schema`
entry in the expected-migrations list) is green.

### 5.6 No-auth handler path accidentally added in 4A.2

**Mitigated by** Spring Security's default deny + 3I.1 ArchUnit
rules (Rule 04: `PermitAll` restricted to `/health`, `/info`,
`/readiness`, `/liveness`). Any 4A.2 patient endpoint that
forgets explicit `@PreAuthorize` gets 401 by default. The
3J.1 `WriteGate` perimeter additionally enforces that mutation
endpoints flow through an `AuthzPolicy`.

### 5.7 `patient_identifier.value` holding PHI without row-level
authz

**Prevented by identifier transitivity.** The satellite's RLS
policies delegate visibility to the parent row via EXISTS
subquery, which inherits the parent's both-GUCs + membership
+ DELETED gates. No independent auth path on identifiers;
they share the parent's access envelope by construction.

### 5.8 CHECK constraint drift

New `AdministrativeSex`, `PatientStatus`, `MrnSource`,
`PatientIdentifierType` enums are governed by the same closed-
enum discipline as `ErrorCodes`, `AuditAction`,
`MedcoreAuthority`. Any addition requires a migration +
matching Kotlin enum update + test. The mapping-test that
covers `administrative_sex` CHECK violations is an existence
check — it does not cover every wire value, but a new enum
entry requires both an SQL CHECK-constraint migration and a
Kotlin enum addition, so drift is structurally visible in
code review.

---

## 6. Framework additions (4A.1)

### 6.1 `MedcoreAuthority` +3 entries + `MembershipRoleAuthorities`
map extension

Three new closed-enum entries:
- `PATIENT_READ` (`MEDCORE_PATIENT_READ`)
- `PATIENT_CREATE` (`MEDCORE_PATIENT_CREATE`)
- `PATIENT_UPDATE` (`MEDCORE_PATIENT_UPDATE`)

Role assignments (per design-pack §4A.1 documented
simplification):
- **OWNER** — all three (+existing tenancy authorities).
- **ADMIN** — all three (+existing tenancy authorities,
  minus `TENANT_DELETE`).
- **MEMBER** — `PATIENT_READ` only.

Clinical role differentiation (CLINICIAN / NURSE / STAFF) is
a future slice when a pilot clinic demands finer-grained
grants. 4A.1 keeps roles coarse so the WriteGate perimeter
has something non-trivial to enforce on day one.

`MembershipRoleAuthoritiesTest` updated with
`containsExactlyInAnyOrder` assertions on all three roles +
an explicit `doesNotContain(TENANT_DELETE)` for ADMIN + new
`doesNotContain(PATIENT_CREATE)` / `doesNotContain(PATIENT_UPDATE)`
for MEMBER.

### 6.2 No WriteGate changes

4A.1 adds no new gate beans, no new handlers, no new
policies. The 4A.2 slice wires through the existing 3J.1
WriteGate without any framework change.

### 6.3 No 3G exception mapping changes

No new `ErrorCodes` entries. 4A.2 may add
`clinical.patient.duplicate` (duplicate-warning) or
`clinical.patient.not_found`; those are 4A.2 review
concerns.

### 6.4 `clinical` added to Flyway scan path

`apps/api/src/main/resources/application.yaml` and
`apps/api/flyway.conf` updated so Flyway discovers
`db/migration/clinical/`. No behavioural change to other
Flyway locations.

### 6.5 Entity discipline

`PatientEntity` + `PatientIdentifierEntity` follow the
established Medcore conventions:
- Regular class (not `data class`) — generated equality on
  every field is wrong for entities, and toString could leak
  PHI in log lines.
- `@Version` on `row_version` — optimistic concurrency.
- Protected no-arg constructor for JPA reflection.
- `administrative_sex` stored as FHIR wire value via a typed
  property accessor; no `AttributeConverter` yet (landing the
  converter is an ergonomic refinement if 4A.2+ mapping
  accumulates verbosity).
- Bare-UUID FKs across module-adjacent boundaries
  (`patient_identifier.patient_id` is a plain `UUID`, not a
  JPA `@ManyToOne`). Keeps the satellite independently
  loadable and matches the tenancy convention.

---

## 7. Conclusion

Phase 4A.1 lands Medcore's first PHI-bearing SQL surface
(`clinical.patient` + `clinical.patient_identifier`) with
**zero application-layer reachability**. Every access path is
gated by RLS policies that key on both the tenant GUC and the
user GUC, fail closed when either GUC is missing, enforce
ACTIVE membership, enforce OWNER/ADMIN role for mutations,
and hide `DELETED` rows from the normal SELECT path. Database-
level CHECK constraints enforce the enum + merge-coherence
invariants without relying on application validation. The
`fuzzystrmatch`-based index + the FHIR-aligned column layout
anticipate the 4A.2 duplicate-warning handler and the 4A.4
FHIR mapper respectively, but neither is accessible from this
commit.

The PHI review surface is narrow because 4A.1 is deliberately
surfaceless: no controller, no service, no handler, no read
endpoint. The real PHI-handling review happens with 4A.2 when
the `CreatePatientHandler` + `UpdatePatientDemographicsHandler`
wire through the 3J.1 WriteGate perimeter — at which point
the ArchUnit Rule 13 `.allowEmptyShould(true)` allowance is
removed, forcing every clinical service to depend on
`PhiSessionContext` at compile time.

**Risk determination confirmed: Low.** The 4A.0 substrate +
the 4A.1 schema + the 4A.2 WriteGate-handler pattern compose
into a defence-in-depth PHI perimeter where each layer
independently enforces the same invariant (tenant scoping +
membership + role). Any one layer could hypothetically fail
closed; all three layers failing closed is what "defense in
depth" actually means.

**Follow-up carry-forwards opened by 4A.1:** None. The
allowance in ArchUnit Rule 13 is already marked to close in
4A.2 when the first clinical service class lands; it is not
a new carry-forward, just an existing open note that 4A.2
resolves. SSN, race/ethnicity, and patient merge are all
queued as subsequent 4A.x slices with their own review packs;
they are NOT carry-forwards from 4A.1.

---

**Sign-off:** 4A.1 cleared for commit on `main`. Proceed to
Phase 4A.2 (patient create/update handlers + endpoints + read
auditing hooks for 4A.5).
