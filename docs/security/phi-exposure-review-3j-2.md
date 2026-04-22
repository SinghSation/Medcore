# PHI Exposure Review — Phase 3J.2 (First tenancy write through WriteGate)

**Slice:** Phase 3J.2 — `PATCH /api/v1/tenants/{slug}` for
display-name updates. First concrete command through the Phase 3J.1
WriteGate framework. Introduces:
`UpdateTenantDisplayNameCommand`, `UpdateTenantDisplayNameValidator`,
`UpdateTenantDisplayNamePolicy`, `UpdateTenantDisplayNameHandler`,
`UpdateTenantDisplayNameAuditor`, `TenantSnapshot`,
`TenantWriteConfig`, `UpdateTenantRequest` DTO,
`TenantSummaryResponse.from(snapshot)` factory, PATCH method on
`TenantsController`. Plus framework-level additions:
`WriteTxHook` interface, `TenancyRlsTxHook` component,
`WriteValidationException` + 3G handler mapping,
`AuthorityResolution` sealed return type refactor of
`AuthorityResolver`, `AuditAction.TENANCY_TENANT_UPDATED`.

**Reviewer:** Repository owner (solo).
**Date:** 2026-04-22.
**Scope:** All Phase 3J.2 code + tests + docs. No new migrations.

**Risk determination:** None.

---

## 1. What this slice handles

The only user-submitted data this slice accepts is the JSON body
`{"displayName": "..."}` sent to `PATCH /api/v1/tenants/{slug}`.

- **`displayName`** — human-readable tenant name (e.g., "Acme
  Health, P.C."). Workforce-facing metadata; NOT PHI. Set by
  tenant owners/admins, visible to every member of the tenant,
  and already returned by the existing read endpoints.
- **`{slug}` path variable** — caller-facing tenant identifier.
  Already visible in URLs for the read surface; not PHI.
- **`Idempotency-Key` header (optional)** — client-chosen opaque
  string. Shape-only in 3J — the framework does not read, dedupe,
  or log the value. No PHI path.

No clinical data, no patient attribute, no free-form note body
touches this slice. Medcore remains pre-Phase-4A.

---

## 2. Data-flow review

### 2.1 Controller entry

The controller reads `principal`, `slug`, request body, and
`Idempotency-Key` header. `principal` is the Phase 3A.3
`MedcorePrincipal` (no PHI). Body is validated by Spring's
`@Valid` (bean validation), then normalised via `trim()`, then
packaged into `UpdateTenantDisplayNameCommand(slug, displayName)`.

### 2.2 Validator

`UpdateTenantDisplayNameValidator` enforces three rules on the
already-trimmed value:
- `displayName.isBlank()` → throw `WriteValidationException(
  field = "displayName", code = "blank")`
- `displayName.length > 200` → `code = "too_long"`
- ISO-control-character presence → `code = "control_chars"`

On rejection: the exception carries the field NAME and a short
stable CODE — never the rejected value. `GlobalExceptionHandler`
maps to 422 with `details.validationErrors = [{field, code}]`.
**No rejected-value echo**, consistent with Phase 3G's
`ErrorResponsePhiLeakageTest` discipline.

### 2.3 Policy

`UpdateTenantDisplayNamePolicy` calls
`AuthorityResolver.resolveFor(userId, slug)` and throws
`WriteAuthorizationException(reason)` on denial. The resolver's
sealed `AuthorityResolution` carries only enum reasons
(`NOT_A_MEMBER`, `INSUFFICIENT_AUTHORITY`, `TENANT_SUSPENDED`).
No row content, no PHI.

### 2.4 WriteTxHook

`TenancyRlsTxHook.beforeExecute` sets the Postgres GUC
`app.current_user_id` to the caller's UUID inside the gate's
transaction. UUID is stable internal identifier; not PHI. The
hook does not log. No PHI path.

### 2.5 Handler

`UpdateTenantDisplayNameHandler.handle` reads the tenant entity
by slug, compares the new `displayName` to the current value, and
either updates-and-saves or short-circuits with `changed = false`.
Returns `TenantSnapshot(id, slug, displayName, status, changed)`.
All fields are tenant metadata; no PHI.

### 2.6 Auditor

`UpdateTenantDisplayNameAuditor.onSuccess` emits an
`audit.audit_event` row with:
- `action = tenancy.tenant.updated`
- `actor_id = <user UUID>`
- `tenant_id = <tenant UUID>`
- `resource_type = "tenant"`
- `resource_id = <slug>` — stable caller-facing identifier
- `outcome = SUCCESS`
- `reason = "intent:tenant.update_display_name"` — fixed string,
  NOT the submitted displayName
- **No before/after state.** The audit row preserves timestamp +
  intent; the tenant's current state lives in `tenancy.tenant`.
  A deliberate design call (ADR-007 §7.1) — diff-reconstruction
  via structured payload is deferred to a future ADR when
  compliance review demands it.

`onDenied` emits:
- `action = authz.write.denied`
- `tenant_id = null` (tenant may not exist; we do NOT probe on
  denial to avoid enumeration-via-timing)
- `resource_id = <slug>` — preserves traceability for attacks
  against specific slugs
- `reason = "intent:tenant.update_display_name|denial:<code>"` —
  closed-enum denial code only, no command payload

No-op path: `changed = false` → auditor returns early, NO row
written. Preserves "every persisted change emits an audit row";
a no-op persists no change.

### 2.7 Response

`WriteResponse<TenantSummaryResponse>(data, requestId)`. Response
body carries: tenant id (UUID), slug, displayName, status,
requestId (MDC). Same shape the existing read surface returns.
No new fields, no new leakage surface.

---

## 3. Log-emission review

New log sites in Phase 3J.2:

- `WriteGate.apply()` on denial-audit failure: **ERROR**,
  `"WriteGate: denial audit emission failed for reason=<code>"`.
  Reason code is the closed enum slug; no command payload.
  Unchanged from 3J.1.
- `UpdateTenantDisplayNameHandler` does NOT log.
- `UpdateTenantDisplayNameAuditor` does NOT log (audit emission
  IS the observability channel for mutations).
- `UpdateTenantDisplayNamePolicy` does NOT log.
- `TenancyRlsTxHook` does NOT log.

`LogPhiLeakageTest` (Phase 3F.1) and `TracingPhiLeakageTest`
(Phase 3F.2) continue to pass — no new log sites emit
bearer/email/display-name tokens, no new `medcore.*` observation
attributes are introduced.

---

## 4. Attack-surface considerations

### 4.1 `displayName` containing control characters

A caller could submit `displayName = "Acme\nHealth"` (newline
embedded). Newlines could break terminal displays, audit-row
greppability, or log-aggregation viewers. Validator rejects
any ISO control character → 422 `control_chars`. Confirmed by
`UpdateTenantDisplayNameValidatorTest`.

### 4.2 Slug probing for tenant enumeration

Unauthenticated callers get 401; authenticated callers probing
slugs get 403 with the uniform `"Access denied."` message
regardless of whether the tenant exists. `AuthorityResolver`
collapses unknown-slug into `NOT_A_MEMBER`. The audit row for a
denial captures the probed slug (so operators can trace
enumeration attempts), but the HTTP response body is identical
whether the tenant exists or not.

### 4.3 RLS backstop on direct DML bypass

Even if a future developer writes raw JDBC UPDATE against
`tenancy.tenant`, V12's RLS write policy refuses unless the
caller's `app.current_user_id` GUC matches an ACTIVE OWNER/ADMIN
membership. `TenancyRlsWriteTest` (Phase 3J.1) already proved
this; Phase 3J.2's integration test exercises the happy path
through the GUC-setting `TenancyRlsTxHook`.

### 4.4 Optimistic-lock race (409)

Two concurrent PATCHes against the same tenant: JPA's `@Version`
bumps `row_version` on flush; the second caller's save throws
`OptimisticLockingFailureException` → 3G maps to 409
`resource.conflict`. Consistent with the existing write-path
behaviour. No PHI surface — both writers are authenticated,
both are ADMIN/OWNER.

### 4.5 `Idempotency-Key` as a correlation leak

A client-chosen idempotency key could theoretically carry PHI if a
misbehaving client packs patient data into the header. Phase 3J.2
does NOT persist, log, or echo the value. Storage + replay
semantics land in a future slice (Phase 4A+); that slice's PHI
review examines the full round-trip before the header becomes
load-bearing.

### 4.6 `WriteValidationException` leakage

The new `WriteValidationException(field, code)` carries a
`message` for internal logging ("validation failed: field=X
code=Y") but `GlobalExceptionHandler.onWriteValidation` emits
ONLY the `{field, code}` pair in the response body — never the
exception's `message` or `cause`. Confirmed by reading the
handler.

### 4.7 Audit row contents

Both success and denial audit rows contain:
- UUIDs (actor, tenant) — stable internal identifiers
- Enum slugs (action, outcome, reason with closed-enum codes)
- Slug string (resource_id)

Nowhere does the auditor serialise the submitted displayName,
old displayName, or any caller-supplied string beyond the slug
(already present in the URL / audit timeline). ADR-003 §3's
"flat, typed, no free-form maps" discipline is preserved.

---

## 5. Framework-level changes

### 5.1 `AuthorityResolution` sealed refactor

Replaced `Set<MedcoreAuthority>` return type with
`AuthorityResolution.Granted | Denied(reason)`. Consumers are
solely Phase 3J.2 code (the resolver is otherwise unused). No PHI
surface — the sealed result carries enum reasons only.

### 5.2 `WriteTxHook` + `TenancyRlsTxHook`

Framework seam for caller-dependent tx-local state. Receives
`WriteContext(principal, idempotencyKey)`. No PHI surface —
the hook's single operation is setting a GUC to the caller's
UUID.

### 5.3 `WriteValidationException` + 422 mapping

New domain exception + one new `@ExceptionHandler` on
`GlobalExceptionHandler`. The handler emits the same
`validationErrors = [{field, code}]` shape Spring's bean-
validation path already uses. No new response-body leakage.

### 5.4 Known limitation — RLS-collapsed `MEMBERSHIP_SUSPENDED`

Documented in `AuthorityResolver` KDoc and
`AuthorityResolverIntegrationTest`. V8's read policy on
`tenancy.tenant` hides the tenant row from suspended members;
the resolver can only observe `NOT_A_MEMBER` on that code path.
Carry-forward to a future V13+ SECURITY DEFINER function for
the resolver, tracked in ADR-007 §7.1.

Compliance impact: a suspended member who attempts to update is
correctly denied (403 + `AUTHZ_WRITE_DENIED` audit). The denial
audit carries `denial:not_a_member` rather than
`denial:membership_suspended`. Forensic reconstruction still
works — the `tenancy.tenant_membership` table preserves the
SUSPENDED row; correlating by user id + tenant id identifies the
membership that was refused.

---

## 6. Conclusion

Phase 3J.2 introduces no new PHI paths. The slice exercises the
Phase 3J.1 WriteGate framework with tenancy metadata only — no
clinical data, no patient attribute, no free-form body with
potential for PHI leakage. The single user-submitted field
(`displayName`) is workforce-facing metadata, already visible on
the read surface. Control-character rejection, non-echo of
rejected values, enum-only denial reasons, and the uniform 403
message hold the existing leakage discipline.

Framework-level additions (`WriteTxHook`, `TenancyRlsTxHook`,
`WriteValidationException`, sealed `AuthorityResolution`) are
reviewed individually and carry no PHI surface. The known
RLS-induced collapse of `MEMBERSHIP_SUSPENDED` into
`NOT_A_MEMBER` is documented with a concrete remediation path
(V13+ SECURITY DEFINER resolver), and preserves compliance
investigability via the membership table itself.

**Risk: None.**
