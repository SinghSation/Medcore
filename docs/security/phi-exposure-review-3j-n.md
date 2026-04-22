# PHI Exposure Review — Phase 3J.N (Membership role update + removal)

**Slice:** Phase 3J.N —
`PATCH /api/v1/tenants/{slug}/memberships/{id}` (role update) +
`DELETE /api/v1/tenants/{slug}/memberships/{id}` (soft-delete).
Closes the `MEMBERSHIP_ROLE_UPDATE` + `MEMBERSHIP_REMOVE`
authorities from the 3J.1 taxonomy. Introduces the **last-OWNER
aggregate-state invariant** as Medcore's first first-class
multi-row rule. New: two command stacks (Update role / Revoke),
`LastOwnerInvariant` helper with pessimistic row locking,
`WriteConflictException` + 3G 409 mapping, V13 admin-read RLS
policy + `caller_is_tenant_admin` SECURITY DEFINER function,
`medcore_rls_helper` NOLOGIN role with BYPASSRLS, WriteGate
extension to route handler-thrown `WriteAuthorizationException`
through `onDenied`, two new `AuditAction` entries.

**Reviewer:** Repository owner (solo).
**Date:** 2026-04-22.
**Scope:** All Phase 3J.N code + tests + V13 migration + docs.

**Risk determination:** None.

---

## 1. What this slice handles

The only user-submitted data this slice accepts:
- **PATCH body** — `{"role": "OWNER"|"ADMIN"|"MEMBER"}`. Closed-
  enum value. Workforce-facing metadata, not PHI.
- **DELETE** — no body. Path variables only.
- **Path variables** — `{slug}` (caller-facing tenant identifier)
  and `{membershipId}` (UUID). Both visible on the read surface.
- **`Idempotency-Key` header (optional)** — shape-only
  passthrough, same status as 3J.3.

No clinical data, no patient attribute, no free-text body. Medcore
remains pre-Phase-4A.

---

## 2. Data-flow review

### 2.1 Controller entry

Controllers for both endpoints read `principal`, path variables,
optional `Idempotency-Key`. `@Valid` enforces `@NotNull` on the
role field (PATCH only). Controllers build the command + capture
`WriteContext` in the closure passed to `WriteGate.apply`, then
call handler with `(cmd, context)`. No user-supplied text reaches
the domain layer.

### 2.2 Policy — three layered checks in two layers

**Policy-layer (pre-transaction):**
1. **Base authority.** Caller must hold
   `MedcoreAuthority.MEMBERSHIP_ROLE_UPDATE` or
   `MEMBERSHIP_REMOVE`.
2. **Promotion-to-OWNER escalation guard** (PATCH only). If
   `newRole == OWNER`, caller must hold `TENANT_DELETE`.

**Handler-layer (intra-transaction), new in 3J.N:**
3. **Target-OWNER guard.** If target's current role is OWNER,
   caller must hold `TENANT_DELETE`. Evaluated in the handler
   because the target row is visible only after
   `TenancyRlsTxHook` has set `app.current_user_id` inside the
   gate's tx (which happens after the policy runs). Thrown
   `WriteAuthorizationException` is caught by `WriteGate` and
   routed through `onDenied` — same audit treatment as any
   policy-thrown denial.

All three checks produce `auth.forbidden` on the wire (Phase 3G
uniform message). Forensics reconstructs via the actor's
recorded role + timestamp.

### 2.3 Last-OWNER invariant — `LastOwnerInvariant` helper

Handler calls `assertAtLeastOneOtherActiveOwner(tenantId)` when
the operation removes one active OWNER from the set (OWNER →
non-OWNER demotion; OWNER revocation from ACTIVE). The helper
acquires a `PESSIMISTIC_WRITE` row lock on all active OWNER
rows via a JPA `@Lock(LockModeType.PESSIMISTIC_WRITE)` query.
Concurrent demotions serialise — the second transaction blocks
on the row lock, re-reads after the first commits, sees `count=1`,
and correctly rejects with `WriteConflictException("last_owner_in_tenant")`
→ 409 `resource.conflict` + `details.reason = "last_owner_in_tenant"`.

**No PHI path.** The helper reads only `(id, tenant_id, role,
status)` columns; no clinical data touches it.

### 2.4 Handler — target-OWNER guard + no-op + last-owner +
mutation

Both handlers follow the same shape:
1. Load tenant by slug.
2. Load target membership by id; 404 if missing or cross-tenant
   (existence-leak mitigation).
3. Target-OWNER guard (see §2.2).
4. No-op detection:
   - **PATCH role:** if `target.role == command.newRole`, return
     `changed=false`, auditor suppresses.
   - **DELETE:** if `target.status == REVOKED`, return
     `changed=false`, auditor suppresses (idempotent 204).
5. Last-OWNER check if operation would orphan the tenant.
6. Apply mutation + stamp `updated_at`. JPA `@Version` bumps
   `row_version` on flush.

### 2.5 Auditor — shape contract extension

Extends the 3J.3 normative contract. Both success and denial rows
use `resource_type = "tenant_membership"` and
`resource_id = <target membership UUID>`. For 3J.N the target
always exists (URL path carries a concrete id), so the membership
UUID is the natural key on both paths — breaking the 3J.3
asymmetry (where the invited membership had no UUID yet).
Documented in `UpdateTenantMembershipRoleAuditor` KDoc.

**Success `reason`** encodes closed-enum transition tokens:
- Role update: `intent:tenancy.membership.update_role|from:OWNER|to:ADMIN`
- Revoke: `intent:tenancy.membership.remove|prior_role:OWNER`

Forensics reconstruct state transitions without joining
historical rows. Values are closed-enum (`OWNER | ADMIN | MEMBER`),
no PHI, no caller-supplied text.

**Denial `reason`** carries the specific
`WriteDenialReason.code` slug (`not_a_member`, `tenant_suspended`,
`insufficient_authority`) — same pattern as 3J.2/3J.3.

### 2.6 No-op suppression preserves the "change ⇒ audit" invariant

`changed = false` on both handlers means no DB mutation fired
(JPA's dirty checking would also skip a no-op save, but we
short-circuit explicitly for determinism). Auditor returns early.
"Every persisted change emits an audit row" holds because
no-ops persist no change.

---

## 3. V13 migration — RLS policy expansion + SECURITY DEFINER helper

### 3.1 Why V13 exists

V8's `p_membership_select_own` on `tenancy.tenant_membership`
limits SELECT visibility to the caller's own rows (`user_id =
current_user_id`). Correct for read endpoints, but blocks the
write flow's pre-load: admins modifying OTHER users' memberships
cannot SEE those memberships. Without V13, every 3J.N success
path returns 404.

### 3.2 What V13 adds

- **`medcore_rls_helper` role** — NOLOGIN, NOSUPERUSER,
  BYPASSRLS. Cannot connect directly. Exists solely to own
  SECURITY DEFINER functions that need to bypass RLS for
  internal checks.
- **`tenancy.caller_is_tenant_admin(tenant_id, caller_id)` function**
  — SECURITY DEFINER, owned by `medcore_rls_helper`,
  `SET search_path = tenancy, pg_temp`, EXECUTE granted only to
  `medcore_app` + `medcore_migrator`. Returns boolean.
- **`p_membership_select_by_admin_or_owner` policy** — OR'd
  additively with V8's own-rows policy. Calls the SECURITY
  DEFINER function (which bypasses the recursion that a direct
  EXISTS subquery would cause).

### 3.3 Threat model

- **Helper role:** NOLOGIN prevents direct authentication. No
  TCP/IP or Unix-socket path to exercise its BYPASSRLS authority
  outside the defined function.
- **SECURITY DEFINER function:** `SET search_path = tenancy,
  pg_temp` closes the classic schema-shadowing attack on
  SECURITY DEFINER functions. The function body is a single
  `SELECT EXISTS` with bound parameters — no dynamic SQL, no
  string interpolation.
- **EXECUTE grants:** `medcore_app` and `medcore_migrator` only.
  `REVOKE ALL ... FROM PUBLIC` runs first so no ambient grant
  survives.
- **Admin visibility scope:** OWNER/ADMIN can now SELECT every
  membership row in THEIR tenant. They still cannot see
  memberships in other tenants — the policy's USING clause keys
  on `tenancy.tenant_membership.tenant_id`, filtered by the
  caller's ACTIVE OWNER/ADMIN presence in that tenant.

### 3.4 Existing tests

No existing RLS test is broken by V13. `TenancyRlsTest` and
`TenancyRlsWriteTest` continue to pass — they test cross-tenant
refusal, not within-tenant admin visibility.

---

## 4. Log-emission review

No new application log sites added. WriteGate's handler-thrown
`WriteAuthorizationException` path emits the same ERROR log as
policy-thrown denials if the auditor `onDenied` fails (same
discipline as 3J.1). Handlers, policies, validator, auditor,
`LastOwnerInvariant` are all silent.

`LogPhiLeakageTest` (3F.1) + `TracingPhiLeakageTest` (3F.2)
continue to pass. No new `medcore.*` observation attributes.

---

## 5. Attack-surface considerations

### 5.1 Last-OWNER bypass via race

**Prevented** by the `PESSIMISTIC_WRITE` row lock. Two concurrent
OWNER-demotion transactions serialise at the lock; the second
re-reads after the first commits, sees `count=1`, and correctly
rejects. Without the lock, READ COMMITTED isolation allows both
to see `count=2` and both to commit → tenant has 0 OWNERs.

**Not prevented:** phantom-INSERT of a new OWNER concurrent with
a demotion. Risk is low (INSERT requires `MEMBERSHIP_INVITE` +
`TENANT_DELETE` for OWNER role, i.e., another OWNER acting). The
conservative-failure outcome is "we might reject a demotion that
would have been safe had the INSERT committed first" — acceptable.
Future V13+ CHECK trigger at commit time closes this window.

### 5.2 Cross-tenant ID probing

A caller with `MEMBERSHIP_ROLE_UPDATE` in tenant A passes a
membership ID from tenant B. Handler loads the membership,
checks `target.tenantId != tenant.id`, returns 404
`resource.not_found`. Response body is identical to "unknown
membership ID" — no existence information leaks.

### 5.3 Privilege escalation via ADMIN → OWNER promotion

**Prevented** by the policy's escalation guard: `newRole == OWNER`
requires the caller to hold `TENANT_DELETE` (OWNER-only). Tested
by `ADMIN promotes MEMBER to OWNER — 403`.

### 5.4 Privilege escalation via ADMIN demoting OWNER

**Prevented** by the handler's target-OWNER guard: `target.role
== OWNER` requires the caller to hold `TENANT_DELETE`. Tested by
`ADMIN modifies OWNER — 403 + target-OWNER guard` and
`ADMIN revokes OWNER — 403 target-OWNER guard`.

### 5.5 Self-revoke of last OWNER

**Prevented** by the last-OWNER invariant. An OWNER CAN revoke
themselves, but only if another active OWNER exists. Tested by
`sole OWNER revokes self — 409` and `OWNER revokes co-OWNER when
2 OWNERs exist — 204`.

### 5.6 Audit-row contents

All rows contain UUIDs (actor, target membership, tenant),
closed-enum role/status values, slug strings, closed-enum
intent / denial codes. No caller-supplied text beyond the slug
(which is already in the URL). ADR-003 §3's flat-schema
discipline preserved.

### 5.7 WriteConflictException response shape

The 3G handler emits `{code: "resource.conflict", message:
"The request conflicts with the current state of the resource.",
requestId: ..., details: {reason: "last_owner_in_tenant"}}`.
The `details.reason` value is a closed-enum slug — no user-
supplied content, no internal state detail, no stack trace.

---

## 6. Framework additions (3J.N)

### 6.1 `WriteConflictException` + 3G 409 mapping

New in `platform/write/`. Mirrors `WriteValidationException`
(3J.2) pattern. The single `@ExceptionHandler` in
`GlobalExceptionHandler.onWriteConflict` emits 409 with
`details.reason = <code>`. No PHI path.

### 6.2 WriteGate extension: handler-thrown
`WriteAuthorizationException` routed through `onDenied`

Small framework extension. The gate now catches
`WriteAuthorizationException` around the transaction block and
routes it through `onDenied` the same way as policy-thrown
denials. Transaction has already rolled back, so no partial
mutation. Audit row emitted for the denial. Same wire contract
(403 `auth.forbidden` + audit row) as before. Documented in
ADR-007 §2.13.

### 6.3 `LastOwnerInvariant` helper with pessimistic locking

Concentrates the aggregate-state check in one testable unit.
First consumer of the "callers invoke helper when the op would
reduce some aggregate" pattern. Future similar invariants
(minimum-active-users, etc.) follow the same shape.

### 6.4 Authority map expansion

OWNER + ADMIN sets both gain `MEMBERSHIP_ROLE_UPDATE`. The
target-OWNER and escalation guards prevent the authority from
being misused for privilege escalation.

---

## 7. Conclusion

Phase 3J.N introduces no new PHI paths. The slice exercises the
Phase 3J.1 framework with tenancy-metadata mutations only
(role changes + revocations). All user-submitted inputs are
closed-enum values or UUIDs. The three escalation guards
(promotion-to-OWNER, target-OWNER, last-OWNER) close every real
privilege-escalation vector in the multi-admin tenant model.
The last-OWNER invariant establishes the pattern for future
aggregate-state rules.

V13's new RLS policy + SECURITY DEFINER helper is the
architecturally correct resolution of the admin-read gap that
V8 left. The BYPASSRLS power is encapsulated in the function
body via a NOLOGIN helper role — no one can exercise the
authority outside the single-purpose function. Admin visibility
within one's own tenant is expanded; cross-tenant isolation is
unchanged.

**Risk: None.**
