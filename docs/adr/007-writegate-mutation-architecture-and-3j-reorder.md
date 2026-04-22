# ADR-007: WriteGate mutation architecture and Phase 3J / 3I reorder

- **Status:** Accepted
- **Date:** 2026-04-22
- **Authors:** Gurinder Singh
- **Reviewers:** Gurinder Singh (repository owner)
- **Supersedes:** none (amends `docs/product/02-roadmap.md` phase order)
- **Related:** ADR-001, ADR-002, ADR-003, ADR-004, ADR-005, ADR-006

---

## 1. Context

Phase 3H closed the deployment-posture carry-forwards from 3E and
marked Medcore as operationally deployable. The next gate before
Phase 4A (patient registry — first clinical module) is
**role-based write enforcement**. Without it, Phase 4A cannot
land without later rework: patient mutations need the same
authz-audit-transaction-RLS pattern that tenancy mutations will
establish first.

Two architectural decisions are coupled tightly enough to share an
ADR:

1. **Mutation architecture.** Every state change in Medcore — now
   (tenancy writes) and forever (patient, encounter, note, order,
   medication, anything) — must pass through a single governed
   boundary. Without that boundary, every module re-invents
   validate / authz / audit / transaction ordering, and drift
   becomes guaranteed.

2. **Phase order.** `02-roadmap.md` originally listed 3I
   (deployment baseline) before 3J (tenancy writes + RBAC). That
   order assumed deployment-infrastructure maturity was the next
   priority. It is not. **Mutation correctness precedes
   deployment readiness:** Medcore will write real business data
   through the mutation boundary long before it is hosted on real
   cloud infrastructure (local dev against Testcontainers is the
   right substrate for Phase 4A). Building AWS before RBAC builds
   a clean hosting environment for a system that isn't yet
   write-safe.

## 2. Decision

**We will establish `WriteGate` as Medcore's single mutation
contract, define a granular `MedcoreAuthority` closed enum with a
role → authority map, wire tenancy writes through the framework
with RLS-write policies as the DB safety net, AND reorder
Phase 3J to come before Phase 3I in `02-roadmap.md`.**

### 2.1 `WriteGate` pipeline

Every mutation executes this six-step pipeline inside
`WriteGate.apply()`. No exceptions. No bypass.

```
validate → authorize → transact-open → apply → audit-success → transact-close
```

Denial fires a separate audit event (`AUTHZ_WRITE_DENIED`) and
re-throws. Denial path does NOT open a transaction. Success path
keeps `apply + audit-success` atomic within the same transaction —
if either throws, the whole mutation rolls back (ADR-003 §2).

### 2.2 `WriteGate` owns the transaction boundary

**Non-negotiable.** The gate injects `PlatformTransactionManager`
and opens its own `TransactionTemplate.execute { ... }`. Callers
MUST NOT rely on enclosing `@Transactional` for mutation
atomicity. This guarantee is the entire point of owning the
boundary here — different callers forgetting or mis-configuring
`@Transactional` was the identified failure mode the user's
Phase 3J pressure test correctly flagged. Moving it into the gate
centralises the invariant.

### 2.3 `WriteContext` with idempotency slot

`WriteGate.apply(command, context, execute)` takes a
`WriteContext(principal, idempotencyKey = null)`. The
idempotency-key slot is shape-only in Phase 3J — the framework
does NOT yet de-duplicate requests. It exists so Phase 4A+
callers don't drive a signature refactor when idempotent
retries land (payment flows, clinical orders, etc.).

### 2.4 Audit intent via `reason` field

Every `WriteAuditor.onSuccess` populates the audit event's
existing `reason` field with an intent code of the form
`intent:<command-class-slug>`. The coarse `action` enum value
(e.g., `TENANCY_TENANT_UPDATED`) plus the fine-grain `reason`
(`intent:tenant.update_display_name`) together distinguish
sibling mutations sharing a coarse action. No schema change —
uses ADR-003 §2's existing column.

### 2.5 Granular authorities

`MedcoreAuthority` is a closed enum of 7 fine-grain authorities:

- `TENANT_READ`, `TENANT_UPDATE`, `TENANT_DELETE`
- `MEMBERSHIP_READ`, `MEMBERSHIP_INVITE`, `MEMBERSHIP_REMOVE`
- `SYSTEM_WRITE`

Splitting `MANAGE` into specific actions pre-emptively means
future OWNER-vs-ADMIN differentiation and new endpoints land
without authority renames. Renaming a shipped authority is a
breaking security-contract change.

**Role → authority mapping (locked):**
- `OWNER` → every tenancy authority INCLUDING `TENANT_DELETE`.
- `ADMIN` → every tenancy authority EXCEPT `TENANT_DELETE`.
- `MEMBER` → `TENANT_READ` + `MEMBERSHIP_READ` only.

`SYSTEM_WRITE` is never in any standard role mapping; see §4.5.

Registry discipline (ADR-005 §2.3 pattern): adding a new
authority requires enum entry + mapping update +
`MembershipRoleAuthoritiesTest` update + review-pack callout.
Renaming / removing requires a superseding ADR.

### 2.6 Tenant-scoped authority resolution

Authorities are **tenant-scoped** — the same user is OWNER of
tenant A, MEMBER of tenant B, stranger to tenant C.
`AuthorityResolver.resolveFor(userId, tenantSlug)` returns the
closed authority set at check-time. Spring Security's
`GrantedAuthority` collection on the Authentication is not used
for tenant-scoped authorities (a stamped-at-auth-time authority
cannot encode "OWNER of A but MEMBER of B"). For system-scope
authorities (SYSTEM_WRITE from a bootstrap JWT claim), the
standard Spring authority model applies.

### 2.7 RLS write policies — the DB safety net

V12 installs RLS policies on `tenancy.tenant` and
`tenancy.tenant_membership`:

- `tenant.INSERT` — denied for `medcore_app` (`WITH CHECK
  false`). System-scope creation uses the SECURITY DEFINER
  function `tenancy.bootstrap_create_tenant(...)` owned by
  `medcore_migrator`; no app-role caller can bypass this.
- `tenant.UPDATE` — caller must have ACTIVE OWNER or ADMIN
  membership in the target tenant.
- `tenant.DELETE` — OWNER only.
- `tenant_membership.INSERT/UPDATE/DELETE` — OWNER or ADMIN of
  the target tenant.

Even if application-layer authz is bypassed by a bug in a
controller that routes around `WriteGate`, Postgres rejects
cross-tenant writes. Defence in depth.

### 2.8 Denial = audited event

`WriteGate` emits `AUTHZ_WRITE_DENIED` for every denial with the
`WriteDenialReason.code` slug in the `reason` field. Closes the
3G carry-forward "audit emission on 403 access-denied." The 403
response body is the uniform "Access denied." from the existing
`AccessDeniedException` handler — the denial reason appears ONLY
in the audit event, never on the wire (Rule 01 §enumeration from
Phase 3G).

### 2.9 Roadmap phase reorder

`docs/product/02-roadmap.md` is amended: **3J (tenancy writes +
WriteGate) now precedes 3I (deployment baseline)**. The DoD
§3.3.x numbering shifts correspondingly. Rationale — mutation
correctness precedes deployment readiness:

- 3J unblocks Phase 4A (patient registry) directly; 3I does not.
- 3J's tests run against local Postgres; no AWS dependency.
- Building AWS before RBAC provisions hosting for a system that
  isn't yet safe to write to.

This is a Tier 3 change to `02-roadmap.md` per ADR-005 §2.3
("phase order"). It lands in the same commit as this ADR.

### 2.10 WriteGate is the exclusive mutation entry point

**Added post-3J.1 pressure test.** Every state-changing operation
on a Medcore domain entity MUST flow through `WriteGate.apply()`.
Direct repository mutations (`repository.save(...)`,
`repository.delete(...)`, `@Modifying` JPQL) outside a
`WriteGate.apply` invocation are forbidden for domain entities.
Exemptions: audit writes (`JdbcAuditWriter.write` — the audit row
IS the governed artifact), chain-verification bookkeeping, and
Flyway migrations (owned by `medcore_migrator`, not the app).

Review-gated until Phase 3I, where an ArchUnit rule lands in CI.
Until then, the discipline is enforced by review: any PR
introducing a domain-entity `.save(...)` outside a handler-under-
WriteGate is rejected.

### 2.11 `WriteTxHook` — caller-dependent tx-local state

**Added in 3J.2** after discovery that V8's RLS policies read
`app.current_user_id`, a `SET LOCAL`-scoped GUC that evaporates
when the policy check's own read-only transaction commits.
`WriteGate` exposes an optional `WriteTxHook` seam invoked AFTER
`transact-open` and BEFORE the handler's `apply`. Phase 3J.2 wires
`TenancyRlsTxHook`, which calls `TenancySessionContext.apply(
userId = context.principal.userId, tenantId = null)` to re-set the
GUC inside the gate's transaction.

Without the hook, every write to an RLS-protected table reads zero
rows in the handler (RLS filters everything). This was discovered
during 3J.2 integration testing — the first end-to-end success
path returned 404 rather than 200. The fix is in the platform
layer so every future mutation inherits the correct behaviour;
future PHI writes (Phase 4A+) will wire a companion
`PhiRlsTxHook` that additionally sets `app.current_tenant_id`.

## 3. Alternatives Considered

### 3.1 Spring AOP with `@Authorized` annotation
**What:** Annotate controller or service methods with
`@Authorized("SOME_AUTHORITY")` + `@Audited`.
**Why rejected:** Hides control flow. A reader of the controller
doesn't see the authz check, doesn't see the audit emission
site. Order-of-operations invariants harder to enforce across
AOP interceptors. Explicit `WriteGate.apply(...)` is visible,
testable, compile-time-linked. Reconsider only if AOP becomes
necessary for cross-cutting concerns with no other home.

### 3.2 `WriteGate` as functional builder, not a class
**What:** `writeGate { validate {...}; authz {...}; audit
{...}; apply {...} }` builder DSL.
**Why rejected:** More compact for writing but harder to read.
Compile-time errors worse (DSL mismatches). Concrete class +
concrete collaborators give clearer stack traces + compile-time
checks.

### 3.3 Keep `@Transactional` on caller (no `WriteGate`-owned tx)
**What:** Service methods have `@Transactional`; the gate
doesn't manage transactions.
**Why rejected:** User's Phase 3J pressure test correctly
identified this as the primary risk. Enforcing invariant in one
place (the gate) is strictly better than enforcing it in N
caller sites.

### 3.4 Don't split 3J ahead of 3I
**What:** Keep the 3I → 3J order from the original roadmap.
**Why rejected:** 3J blocks Phase 4A; 3I does not. Building
deployment infrastructure before write-safety is backwards for a
pre-product system.

### 3.5 Deep authz in JWT claims (stamp authorities at auth time)
**What:** Have the IdP inject tenant-scoped authorities into
the JWT. Convert JWT → Spring `GrantedAuthority` collection at
auth time.
**Why rejected:** Authorities are tenant-scoped; stamping at
auth time requires the IdP to know Medcore's tenant model.
Resolving authorities on-demand via `AuthorityResolver` uses the
live membership data and reflects revocations without requiring
re-authentication.

## 4. Consequences

### 4.1 Positive
- Every mutation, now and forever, has the same validation +
  authz + audit + transaction semantics.
- Denial is provably audited (compliance evidence).
- RLS write policies backstop application bugs.
- Authority granularity supports future role differentiation
  without breaking changes.
- `WriteContext` future-proofs for idempotency without signature
  churn.
- Phase 4A+ inherits the pattern — mechanical reuse.

### 4.2 Negative
- Callers that don't perform mutations don't use `WriteGate` —
  one more abstraction to understand.
- `MedcoreAuthority` enum growth is a forcing function each time
  a new resource class is added (patient, encounter). Expected
  and intended.
- `@Transactional` on service methods that call `WriteGate` is
  now redundant / potentially confusing. Developers must
  understand the gate owns the tx.

### 4.3 Risks & Mitigations
- **A new controller routes around `WriteGate`.** Mitigation:
  review-gate; ArchUnit / Konsist test in Phase 3I CI
  (deferred — not blocking 3J).
- **Authority added without role-mapping update.**
  Mitigation: `MembershipRoleAuthoritiesTest` asserts the full
  contract; new authority without mapping update trips the test.
- **Bootstrap SECURITY DEFINER function misused.** Mitigation:
  EXECUTE granted only to `medcore_migrator`. No app-role caller
  can invoke it. `TenancyRlsWriteTest` asserts this at runtime.
- **Tenant-scoped authority resolution is latency-sensitive.**
  Mitigation: `AuthorityResolver` issues ONE SQL SELECT per
  check. Cache at `ResolvedMembership` level is future work if
  profiling demands it.

## 5. Compliance & Security Impact

- **HIPAA §164.308(a)(4) (access management):** Role-based
  authority model + tenant scoping + audit-on-denial satisfy
  the "procedures for granting access" + "authorization and/or
  supervision" standards.
- **HIPAA §164.312(a)(1) / (b) / (c):** Access control + audit
  controls + integrity. Mutation architecture materially
  strengthens all three.
- **SOC 2 CC6.1 (logical access):** Granular authorities +
  tenant-scoped resolution + RLS backstop = enterprise-grade
  access control pattern.
- **SOC 2 CC6.6 (vulnerability management):** Denial-audit
  visibility makes suspicious access patterns detectable in
  routine log review.
- **SOC 2 CC7.1 (change management):** `WriteGate` is the
  single change point — deviations are detectable in review.
- **21 CFR Part 11 (future):** If Medcore handles regulated
  clinical data, the mutation boundary + audit trail meets the
  §11.10 electronic-records access-control requirements.

## 6. Operational Impact

- Developer workflow: every mutation now requires three small
  concrete classes (policy, auditor, command) alongside the
  controller method. Overhead ~50 lines per mutation.
- Review load: every new mutation reviewed against the
  six-step pipeline + authority mapping.
- Test load: integration tests must cover denial path for every
  authz-gated endpoint.
- Runbook: no new runbook in 3J (authz failures show in the
  audit log).

## 7. Rollout Plan

- This ADR lands in one Tier 3 commit with:
  - `platform/write/` core types (8 files)
  - `platform/security/` authority model (3 files)
  - V12 RLS write policies + bootstrap function
  - `AuditAction.AUTHZ_WRITE_DENIED`
  - Full test coverage (WriteGateTest, MembershipRoleAuthoritiesTest,
    AuthorityResolverIntegrationTest, TenancyRlsWriteTest)
  - Phase reorder in `02-roadmap.md`
  - DoD §3.3 Phase 3J row
  - PHI-exposure review
- **Phase 3J.2 (next):** wire the first concrete tenancy write
  endpoint (PATCH tenant display_name) through the new framework.
  Purely mechanical reuse of the patterns established here.
- **Phase 3J.3+:** remaining tenancy writes (membership CRUD),
  bootstrap admin endpoint (depends on 3K IdP decision).

### 7.1 Carry-forwards opened by 3J.1

Flagged during the 3J.1 review cycle; tracked here so future
slices close them deliberately rather than by drift.

- **`MEMBERSHIP_ROLE_UPDATE` authority.** The 7-entry
  `MedcoreAuthority` covers invite / remove but not "change an
  existing member's role" (e.g., promote MEMBER → ADMIN). When the
  first membership-role-change endpoint lands (3J.N), a new
  authority enters the registry per §2.5 discipline (enum entry +
  `MembershipRoleAuthorities` map update + test update +
  review-pack callout). Authority name locks at that time.
- **`WriteResponse` envelope extensibility.** Current shape is
  `data + requestId`. When the first caller needs metadata
  (pagination, partial-success, async-operation handle,
  idempotency-replay signal), extend the envelope via a **typed
  sealed hierarchy** or named fields — NOT an `Any`-typed
  `Map<String, Any>` grab-bag. A typed extension point preserves
  the Phase 3F.2 allow-list philosophy: operators know exactly
  what can appear in a response, no PHI-shaped values leak through
  an untyped bag. Shape decision deferred to the first caller.
- **`AuthorityResolver` caching.** Per-request DB round-trip is
  correct at current load; a per-JWT authority cache (claim-backed
  or short-lived `Caffeine`) becomes a real concern at Phase 4+
  traffic shapes. A caching-strategy ADR lands then, covering
  invalidation on membership change + explicit staleness bound.
- **Idempotency-key dedupe.** `WriteContext.idempotencyKey` is
  shape-only in 3J; the first command that must be retry-safe
  (Phase 4A+ patient-create adjacency, Phase 6A Stripe webhooks)
  drives the persistence model + replay semantics behind an ADR.

## 8. Acceptance

Closed by:
- [x] `WriteGate<CMD, R>` class with validate → authz →
      transact → apply → audit pipeline
- [x] Transaction ownership moved into the gate
- [x] `WriteContext` with idempotency slot
- [x] `MedcoreAuthority` closed enum with 7 entries
- [x] Role → authority map locked and tested
- [x] `AuthorityResolver` with tenant-scoped resolution
- [x] V12 RLS write policies + SECURITY DEFINER bootstrap
- [x] `AUTHZ_WRITE_DENIED` audit action + emission path
- [x] Phase reorder in `02-roadmap.md`

## 9. References

- ADR-003 (audit append-only + synchronous)
- ADR-004 (tiered execution authority)
- ADR-005 (product direction framework — registry discipline)
- ADR-006 (Phase 3H — role separation at DB layer)
- `docs/security/phi-exposure-review-3j.md`
- HIPAA §§164.308(a)(4), 164.312(a)(1), (b), (c)
- SOC 2 CC6.1, CC6.6, CC7.1
