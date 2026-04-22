# PHI Exposure Review — Phase 3J.3 (Membership invite through WriteGate)

**Slice:** Phase 3J.3 —
`POST /api/v1/tenants/{slug}/memberships`. Second concrete
command through the Phase 3J.1 WriteGate substrate. Introduces:
`InviteTenantMembershipCommand`, `InviteTenantMembershipValidator`,
`InviteTenantMembershipPolicy` (with the OWNER-invites-OWNER
escalation guard), `InviteTenantMembershipHandler`,
`InviteTenantMembershipAuditor`, `MembershipSnapshot`,
`InviteMembershipRequest` DTO,
`MembershipResponse.from(snapshot)` factory, POST method on
`TenantsController`, `AuditAction.TENANCY_MEMBERSHIP_INVITED`,
second `WriteGate` bean in `TenantWriteConfig`.

**Reviewer:** Repository owner (solo).
**Date:** 2026-04-22.
**Scope:** All Phase 3J.3 code + tests + docs. No new migrations
(V6 already carries the `uq_tenancy_membership_tenant_user`
UNIQUE constraint the handler relies on).

**Risk determination:** None.

---

## 1. What this slice handles

The only user-submitted data this slice accepts is the JSON body
`{"userId": "<uuid>", "role": "OWNER|ADMIN|MEMBER"}` and an
optional `Idempotency-Key` header.

- **`userId`** — an existing `identity.user.id` UUID. Stable
  internal identifier; not PHI. Already visible in
  `tenancy.tenant_membership.user_id` on the read surface.
- **`role`** — closed enum (`OWNER` / `ADMIN` / `MEMBER`).
  Workforce-facing metadata, not PHI.
- **`{slug}` path variable** — caller-facing tenant identifier.
  Same shape as on the read surface + Phase 3J.2.
- **`Idempotency-Key` header (optional)** — client-chosen
  opaque string. **Shape-only in Phase 3J.3** — the framework
  accepts and propagates the value into `WriteContext.idempotencyKey`
  but does NOT dedupe retries. Duplicate submissions produce
  409 via the V6 unique constraint. Documented on the
  controller method KDoc + DoD §3.4.3.

No clinical data, no patient attribute, no free-form body
touches this slice. Medcore remains pre-Phase-4A.

---

## 2. Data-flow review

### 2.1 Controller entry

Reads `principal`, `slug`, request body, `Idempotency-Key`.
`@Valid` enforces `@NotNull` on `userId` + `role` before the
controller code runs. Values propagated into
`InviteTenantMembershipCommand(slug, userId, role)` + `WriteContext`.

### 2.2 Validator

`InviteTenantMembershipValidator` covers slug format + blank-slug;
`userId` / `role` null-checks live on the DTO. Any rejection
throws `WriteValidationException` → 422 via the 3G envelope.
No rejected-value echo, consistent with `ErrorResponsePhiLeakageTest`
(Phase 3G).

### 2.3 Policy — two gates

Inside `InviteTenantMembershipPolicy.check`:

1. **Base authority:** caller must hold
   `MedcoreAuthority.MEMBERSHIP_INVITE`. OWNER + ADMIN have it.
   MEMBER does not.
2. **Escalation guard** (ADR-007 §4.9): if `command.role == OWNER`,
   caller must also hold `MedcoreAuthority.TENANT_DELETE` (OWNER-
   only by the role-map). Prevents ADMIN from creating a second
   OWNER account.

Both failures throw `WriteAuthorizationException(
INSUFFICIENT_AUTHORITY)` — identical wire signal (403
`auth.forbidden`). Forensic reconstruction distinguishes the two
via the recorded actor's role in `tenant_membership` at the
audited timestamp.

### 2.4 WriteTxHook

Inherits 3J.2's `TenancyRlsTxHook` (sets `app.current_user_id`
inside the gate's tx). No new surface.

### 2.5 Handler

`InviteTenantMembershipHandler.handle`:
- `tenantRepository.findBySlug` (RLS-gated; caller is
  OWNER/ADMIN per policy, so tenant is visible).
- `identityUserRepository.existsById` on target — MISSING user
  surfaces as 422 `{field:"userId", code:"user_not_found"}`,
  **NOT** 404. Rationale: 404 leaks user-existence; 422 returns
  no value the caller didn't submit.
- Construct `TenantMembershipEntity` with ACTIVE status,
  `Instant.now(clock)` timestamps.
- `membershipRepository.save` — UNIQUE constraint on
  `(tenant_id, user_id)` is the duplicate guard (V6); SQLSTATE
  23505 maps to 409 via Phase 3G. No app-layer pre-check —
  avoids race windows between concurrent invites.

### 2.6 Auditor — structured target-user capture on denial

**Success row:**
- `action = tenancy.membership.invited`
- `reason = intent:tenancy.membership.invite`
- `resource_type = "tenant_membership"`,
  `resource_id = <new membership UUID>`
- `tenant_id = <tenant UUID>`, `actor_id = <caller UUID>`

**Denial row:**
- `action = authz.write.denied`
- `reason = intent:tenancy.membership.invite|denial:<code>`
- `resource_type = "tenant_membership"`,
  `resource_id = <target user UUID>`
- `tenant_id = null` (enumeration protection),
  `actor_id = <caller UUID>`

**Shape asymmetry:** success-row `resource_id` is the new
membership UUID; denial-row `resource_id` is the target user
UUID. This is intentional. A denied invite has no membership UUID
(the row doesn't exist), and the natural identifying key for the
prospective membership is `(tenant_id, user_id)` — with
`tenant_id` deliberately null, the target user UUID is the only
column-level structured identifier. Compliance queries remain
column-based (no `reason` string parsing).

Rationale documented in the auditor KDoc + DoD §3.4.3 +
ADR-007 §7.1 so future auditors adopt the same pattern when they
face the same constraint.

### 2.7 Response

`WriteResponse<MembershipResponse>(data, requestId)` with 201
Created. `MembershipResponse.from(snapshot)` adapts the
`MembershipSnapshot` to the same outbound wire shape the read
surface produces.

---

## 3. Log-emission review

No new application log sites are added. The auditor emits
`audit.audit_event` rows (structured, non-PHI). Handler +
policy + validator are silent. `WriteGate`'s denial-audit-failure
error log site is unchanged from 3J.1.

`LogPhiLeakageTest` (3F.1) and `TracingPhiLeakageTest` (3F.2)
continue to pass — no new log sites, no new `medcore.*`
observation attributes.

---

## 4. Attack-surface considerations

### 4.1 User-existence leak via response shape

Attempting to invite a non-existent user returns 422
`user_not_found`. An inviting a user who exists and is already
a member returns 409 `resource.conflict`. Both carry the 3G
uniform message; neither echoes the submitted UUID back in the
body. Operators can still distinguish the cases via the
response `code` — 422 vs 409 — but this is available only to
already-authenticated admins with `MEMBERSHIP_INVITE` who
could trivially enumerate via other means.

### 4.2 User-existence leak via timing

The non-existence path short-circuits before the membership
INSERT. The existence path performs an additional INSERT. A
determined attacker with precise timing measurement could infer
existence. **Not mitigated in 3J.3** — accepted risk at current
threat model (authenticated internal callers with
`MEMBERSHIP_INVITE`). Tracked implicitly; revisit if external
surface exposes this endpoint.

### 4.3 Privilege escalation via ADMIN-invites-OWNER

Prevented by the policy's secondary check (`TENANT_DELETE`
authority required for `role == OWNER`). Asserted by
integration test `ADMIN invites OWNER — 403 + denial=
insufficient_authority (escalation guard)`.

### 4.4 Self-invite

Allowed at the policy layer (caller must already be a member to
hold `MEMBERSHIP_INVITE`). The V6 unique constraint refuses —
409 `resource.conflict` is the correct signal ("you're already
a member"). Asserted by
`self-invite when caller is already member — 409`.

### 4.5 RLS write-policy backstop

V12's `p_membership_insert_by_admin_or_owner` requires the
caller's `app.current_user_id` GUC to match an ACTIVE OWNER/ADMIN
membership in the target tenant. Even if a future developer
bypasses the policy, Postgres refuses cross-tenant writes. The
existing `TenancyRlsWriteTest` (Phase 3J.1) already proves this;
Phase 3J.3's integration test exercises the end-to-end happy
path via the GUC-setting hook.

### 4.6 Idempotency-Key misuse

Header is accepted but not functional. A client retrying with
the same key will get 409 (unique constraint) instead of the
stored prior response. Controller KDoc + DoD row explicitly
state "clients should NOT assume retry-safe behaviour on this
endpoint yet." Dedupe semantics land in Phase 4A.

### 4.7 Audit row contents

Both rows contain:
- UUIDs (actor, target/membership, tenant) — stable internal
  identifiers
- Closed-enum codes (action, outcome, denial reason)
- Slug string (only as part of the `resource_id` when
  applicable; Phase 3J.3 uses UUIDs in `resource_id` throughout)

No caller-supplied text beyond the slug (already present in the
URL) appears in audit rows. ADR-003 §3's "flat, typed, no free-
form maps" discipline preserved.

---

## 5. Framework-level additions (none new in 3J.3)

3J.3 instantiates the 3J.2 framework without extending it. No
new seam, no new base class, no new 3G handler. The single
platform-level addition is the `AuditAction.TENANCY_MEMBERSHIP_INVITED`
registry entry.

---

## 6. Conclusion

Phase 3J.3 introduces no new PHI paths. The slice exercises the
Phase 3J.1 WriteGate framework with tenancy-metadata mutations
only (user-role membership creation). The one user-submitted
free field — absent — all inputs are UUIDs + closed-enum
values. Control-character concerns do not apply (no free-text
payload). The policy's privilege-escalation guard prevents an
ADMIN from creating an OWNER, closing a real attack vector in
multi-admin organisations. Target-user capture on denial is
structured via the existing `resource_type` + `resource_id`
columns, preserving the ADR-003 §3 flat-schema discipline
without requiring a new migration.

The RLS-collapse limitation inherited from 3J.2
(`MEMBERSHIP_SUSPENDED` → `NOT_A_MEMBER`) applies identically to
this endpoint's denial path. No new carry-forwards; remediation
via V13+ SECURITY DEFINER resolver covers both 3J.2 and 3J.3.

**Risk: None.**
