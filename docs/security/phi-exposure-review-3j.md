# PHI Exposure Review — Phase 3J (WriteGate + Tenancy Writes + RBAC)

**Slice:** Phase 3J.1 — `WriteGate` mutation contract
(`WriteContext`, `WriteValidator`, `AuthzPolicy`, `WriteAuditor`,
`WriteAuthorizationException`, `WriteDenialReason`,
`WriteResponse`), `MedcoreAuthority` 7-entry closed enum,
`MembershipRoleAuthorities` role-to-authority map,
`AuthorityResolver` (tenant-scoped, per-request),
`V12__tenancy_rls_write_policies.sql` (RLS write policies +
`tenancy.bootstrap_create_tenant` SECURITY DEFINER function),
`AuditAction.AUTHZ_WRITE_DENIED` enum entry, ADR-007.

**Reviewer:** Repository owner (solo).
**Date:** 2026-04-22.
**Scope:** All Phase 3J.1 code + migration + ADR. No HTTP write
endpoints, no controllers, no domain commands, no services. This
slice establishes the mutation framework only; the first concrete
tenancy write endpoint lands in Phase 3J.2.

**Risk determination:** None.

---

## 1. What this slice handles

Phase 3J.1 is pure authorization + mutation-boundary machinery.
The data surfaces are:

- **`MedcorePrincipal` fields** (`userId`, `issuerSubject`,
  `email`, `displayName`, `preferredUsername`, etc.) — already in
  the system since Phase 3A.3. Not PHI; workforce-identity metadata.
- **`tenancy.tenant` rows** — slug, display_name, status,
  timestamps, row_version. Not PHI; tenant-identity metadata.
- **`tenancy.tenant_membership` rows** — (tenant_id, user_id, role,
  status) tuples. Not PHI; access-control linkage.
- **`audit.audit_event` rows** — existing append-only audit surface
  governed by ADR-003. Phase 3J.1 adds a new `action` enum value
  (`AUTHZ_WRITE_DENIED`) but does not change row content or
  emission policy.
- **`MedcoreAuthority` enum values** — seven string constants
  (`TENANT_READ`, `TENANT_UPDATE`, ...). Metadata, not PHI.

No clinical data touches this slice. Medcore remains pre-Phase-4A;
no patient entity exists yet.

---

## 2. `WriteGate` pipeline review

The `WriteGate.apply(command, context, execute)` pipeline is:

```
validate → authorize → transact-open → apply → audit-success → transact-close
```

Field-by-field handling at each stage:

### 2.1 `WriteContext`

`WriteContext(principal: MedcorePrincipal, idempotencyKey: String?)`.

- `principal` carries workforce identity fields already present
  since Phase 3A.3 (OIDC resource server). Not PHI.
- `idempotencyKey` is a client-supplied correlation string for
  Phase 4A+ idempotent retries. **Shape-only in 3J.1** — the
  framework does not yet read, dedupe on, or log the value.
  When it becomes load-bearing, a follow-on PHI review will
  re-examine the slot before it is persisted anywhere.

### 2.2 `WriteValidator<CMD>`

Validators run BEFORE authorization and BEFORE any transaction
opens. Validator exceptions propagate without audit emission (a
validation failure is a 4xx client error, not an access-control
decision). Phase 3J.1 ships no concrete validator; the interface
is a seam for Phase 3J.2+ domain commands.

### 2.3 `AuthzPolicy<CMD>`

Policies call `AuthorityResolver.resolveFor(userId, tenantSlug)`
and throw `WriteAuthorizationException(reason)` on denial. The
resolver issues a `SELECT ... FROM tenancy.tenant_membership` —
read-only, no side effects. The denial reason is a closed-enum
`WriteDenialReason` value (`NOT_A_MEMBER`,
`INSUFFICIENT_AUTHORITY`, `TENANT_SUSPENDED`,
`MEMBERSHIP_SUSPENDED`, `SYSTEM_SCOPE_REQUIRED`) — bounded strings,
no row content, no PHI.

### 2.4 `WriteAuditor<CMD, R>`

- `onSuccess(command, result, context)` emits an
  `audit.audit_event` row with the existing schema. Command-class
  name contributes to the audit `reason` slug as
  `intent:<command-class-slug>`. Result values MUST NOT be
  serialised into audit rows by concrete auditors. Phase 3J.1
  ships no concrete auditor; Phase 3J.2's tenancy auditor will
  receive its own PHI review.
- `onDenied(command, context, reason)` emits a fresh audit event
  with `action = AUTHZ_WRITE_DENIED` and `reason = <denial-code>`.
  No command payload serialisation, no principal email, no
  resource body.

### 2.5 Transaction boundary

`WriteGate` injects `PlatformTransactionManager` and runs
`apply + audit-success` inside a single
`TransactionTemplate.execute { ... }`. Denial path does NOT open a
transaction — no side effect, no rollback overhead, no audit row
under an unclosed tx.

---

## 3. `MedcoreAuthority` + role map review

`MedcoreAuthority` is a closed 7-entry Kotlin enum implementing
Spring's `GrantedAuthority`. Each entry is a constant string
(`TENANT_READ`, `TENANT_UPDATE`, `TENANT_DELETE`,
`MEMBERSHIP_READ`, `MEMBERSHIP_INVITE`, `MEMBERSHIP_REMOVE`,
`SYSTEM_WRITE`). No PHI.

`MembershipRoleAuthorities` is a `data object` holding three
`Set<MedcoreAuthority>` constants (OWNER, ADMIN, MEMBER). No PHI.

`AuthorityResolver.resolveFor(userId, tenantSlug)` reads:

```sql
SELECT t.status AS tenant_status, m.role, m.status AS membership_status
  FROM tenancy.tenant t
  JOIN tenancy.tenant_membership m ON m.tenant_id = t.id
 WHERE t.slug = :slug AND m.user_id = :userId
```

Three non-PHI columns in, a `Set<MedcoreAuthority>` out. No
logging of the raw row, no logging of the authority set. The
resolver's return value is only consumed by policies and the JWT
authority converter (Phase 3J.2+); neither surfaces the value to
responses or logs.

---

## 4. Migration content review

`V12__tenancy_rls_write_policies.sql`:

- `CREATE POLICY p_tenant_insert_none ... WITH CHECK (false)` —
  denies direct INSERT on `tenancy.tenant` by `medcore_app`.
  Routing through `tenancy.bootstrap_create_tenant` is the only
  path. Policy contents: hard-coded `false`. No data.
- `CREATE POLICY p_tenant_update_by_admin_or_owner ...` — USING +
  WITH CHECK clauses issue the same membership subquery pattern as
  V8's read policies. Column references only; no literal data.
- `CREATE POLICY p_tenant_delete_by_owner ...` — same pattern,
  scoped to `role = 'OWNER'`. No data.
- `CREATE POLICY p_membership_{insert,update,delete}_by_admin_or_owner
  ...` — three policies on `tenancy.tenant_membership` using the
  same subquery pattern. No data.
- `GRANT SELECT, INSERT, UPDATE, DELETE ON tenancy.tenant TO
  medcore_app;` — role-level grant; RLS above constrains what each
  DML reaches. No data.
- `GRANT SELECT, INSERT, UPDATE, DELETE ON tenancy.tenant_membership
  TO medcore_app;` — same.
- `CREATE OR REPLACE FUNCTION tenancy.bootstrap_create_tenant(
  p_slug TEXT, p_display_name TEXT, p_owner_user_id UUID) RETURNS
  UUID LANGUAGE plpgsql SECURITY DEFINER ...` — function body
  INSERTs into `tenancy.tenant` and `tenancy.tenant_membership`.
  Arguments are bound as parameters (no string interpolation).
  `SET search_path = tenancy, pg_temp` hardens against
  search-path attacks. No PHI in the function body; the function
  never touches clinical schemas.
- `REVOKE ALL ON FUNCTION tenancy.bootstrap_create_tenant(...)
  FROM PUBLIC;` followed by `GRANT EXECUTE ... TO medcore_migrator;`
  — only the out-of-process migrator role can invoke. The
  application role cannot.

No INSERT / UPDATE / DELETE of data in the migration itself. All
DML at runtime goes through the policies above.

---

## 5. Log emission review

New log sites in Phase 3J.1:

- `WriteGate.apply()` on denial-auditor throwing: **ERROR** log
  line with message `"write-gate onDenied threw; original
  denial preserved"`. No command payload, no principal email, no
  reason content (only the `WriteDenialReason` enum name).
- `WriteGate.apply()` does NOT log the command, the principal, the
  result, or the authority set under any other path. Observability
  of mutations is handled by audit emission, not by application
  logs.
- `AuthorityResolver` does NOT log. Its return value is the whole
  communication channel.
- `MembershipRoleAuthorities` and `MedcoreAuthority` are data
  types; no log sites.

`TracingPhiLeakageTest` (Phase 3F.2) continues to assert that no
`medcore.*` observation attribute carries PHI-shaped values. This
slice adds no new Medcore-custom observation names.

`LogPhiLeakageTest` (Phase 3F.1) continues to assert that no known
PHI/credential token surfaces in emitted log output. No new log
sites introduced in this slice emit bearer tokens, emails, or
display names.

---

## 6. Attack-surface considerations

### 6.1 Missing `@Transactional` on a caller

Historically, mutation callers forgetting `@Transactional` split
the "apply + audit" pair across two implicit transactions,
creating rows-without-audit or audit-without-rows. Phase 3J.1
moves transaction ownership INTO `WriteGate`, so caller
annotations are irrelevant. `WriteGateTest.apply-runs-validator-
then-policy-then-execute-then-audit-success-in-order` asserts
the ordering; the `NoopTxManager` test double substitutes a real
manager's boundary for unit speed.

### 6.2 Authority bypass via direct-SQL DML

A compromised application code path issuing raw DML against
`tenancy.tenant` or `tenancy.tenant_membership` would bypass
`AuthzPolicy`. Mitigation: V12 RLS write policies enforce the
same role rules at the DB layer. Without an active
`app.current_user_id` GUC matching an OWNER/ADMIN membership,
Postgres refuses the write regardless of Kotlin-side logic.
`TenancyRlsWriteTest` exercises the RLS envelope end-to-end.

### 6.3 `bootstrap_create_tenant` privilege escalation

`SECURITY DEFINER` functions run as their owner
(`medcore_migrator`) rather than the caller. A mis-granted EXECUTE
would let `medcore_app` create tenants without membership checks.
Mitigation: the migration issues `REVOKE ALL ... FROM PUBLIC;`
before granting EXECUTE exclusively to `medcore_migrator`, and
`TenancyRlsWriteTest.medcore_app-cannot-execute-bootstrap-
create-tenant` asserts the app role cannot invoke. `SET
search_path = tenancy, pg_temp` closes the classic search-path
escalation on SECURITY DEFINER functions.

### 6.4 Denial audit throwing swallows the original 403

If `WriteAuditor.onDenied` throws, a naive implementation would
surface the audit exception to the caller and lose the authz
signal. `WriteGate` catches auditor failures at denial time, logs
ERROR, and re-throws the ORIGINAL `WriteAuthorizationException`.
`WriteGateTest.denial-audit-throwing-does-not-swallow-the-
original-denial` asserts this invariant.

### 6.5 Authority value leak via HTTP response

Granted authorities influence 403 responses. A poorly written
controller could echo "you need authority X" to the caller,
revealing the authority taxonomy. Phase 3G's error envelope
standardisation mandates `"Access denied."` as the IDENTICAL
message for every 403 — `auth.forbidden` and `tenancy.forbidden`
codes share the single string. `ErrorResponsePhiLeakageTest`
(Phase 3G) continues to assert no authority-name substring
surfaces in response bodies.

### 6.6 Shared `AuthorityResolver` caching across users

A mis-implemented cache on `resolveFor(userId, slug)` would leak
one user's authorities to another. Phase 3J.1's resolver holds no
cache — every call issues a fresh query. Caching is deferred to
Phase 4A+ if performance warrants, behind an ADR covering the
invalidation contract.

### 6.7 `SYSTEM_WRITE` privilege grant

`SYSTEM_WRITE` is a privileged authority reserved for
infrastructure callers (backfills, reconciliations, cron jobs).
ADR-007 §4.5 locks the contract: `SYSTEM_WRITE` is never in any
standard role mapping, and granting it requires an explicit ADR
plus an `AuthorityResolver` extension point (not shipped in 3J.1).
No Phase 3J.1 code path issues `SYSTEM_WRITE`; attempting to do so
via hand-crafted `SecurityContext` manipulation is a code-review
concern, not a runtime exposure.

---

## 7. Test coverage over the authz surface

The authorization-decision matrix is exhaustively covered:

- `MembershipRoleAuthoritiesTest` (4): every role constant returns
  the expected authority set; OWNER is a strict superset of
  ADMIN; ADMIN lacks `TENANT_DELETE`; MEMBER holds only read
  authorities.
- `AuthorityResolverIntegrationTest` (7): active OWNER / ADMIN /
  MEMBER paths; suspended membership returns empty; suspended
  tenant returns empty for an active OWNER; non-member returns
  empty; unknown slug returns empty.
- `WriteGateTest` (7): happy-path ordering; validator
  short-circuits; denial emits audit and re-throws; denial-audit
  throwing preserves the original denial; execute-throws skips
  success audit; success-audit-throws propagates; validator-less
  gate still enforces order.
- `TenancyRlsWriteTest` (7): RLS refuses cross-tenant UPDATE /
  DELETE / membership INSERT from `medcore_app`; refuses direct
  INSERT on `tenancy.tenant`; grants to OWNER/ADMIN of own tenant;
  rejects MEMBER role; asserts `bootstrap_create_tenant` is not
  callable as `medcore_app`.

Total Phase 3J.1 test delta: +25 tests across 4 new suites.
All pass against real Postgres via Testcontainers.

---

## 8. Conclusion

Phase 3J.1 introduces no new PHI paths. The slice is pure
authorization plumbing: a mutation pipeline with transaction
ownership, a closed-enum authority taxonomy with a locked role
map, a tenant-scoped resolver, and RLS write policies as the DB
safety net. Every new code path was reviewed for command-payload
serialisation, principal-field exposure, and error-message leak;
none carry PHI-shaped values. The `bootstrap_create_tenant`
SECURITY DEFINER function is EXECUTE-gated to the migrator role
only, and the search_path hardening closes the standard
privilege-escalation vector on such functions.

Phase 3J.2 (first concrete tenancy write endpoint through the
framework) will receive its own PHI-exposure review before merge.

**Risk: None.**
