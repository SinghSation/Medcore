# PHI Exposure Review — Phase 3C (Audit v1)

Performed against the working tree for the Phase 3C Audit v1 slice
prior to commit. Follows the procedure in
`.claude/skills/phi-exposure-review.md`.

## 1. Scope of the diff

**Files changed (22):**

New
- `apps/api/src/main/resources/db/migration/audit/V7__audit_event.sql`
- `apps/api/src/main/kotlin/com/medcore/platform/audit/AuditAction.kt`
- `apps/api/src/main/kotlin/com/medcore/platform/audit/AuditEventCommand.kt`
- `apps/api/src/main/kotlin/com/medcore/platform/audit/AuditWriter.kt`
- `apps/api/src/main/kotlin/com/medcore/platform/audit/JdbcAuditWriter.kt`
- `apps/api/src/main/kotlin/com/medcore/platform/audit/RequestMetadata.kt`
- `apps/api/src/main/kotlin/com/medcore/platform/audit/RequestMetadataProvider.kt`
- `apps/api/src/main/kotlin/com/medcore/platform/security/AuditingAuthenticationEntryPoint.kt`
- `apps/api/src/test/kotlin/com/medcore/audit/AuditIdentityIntegrationTest.kt`
- `apps/api/src/test/kotlin/com/medcore/audit/AuditTenancyIntegrationTest.kt`
- `apps/api/src/test/kotlin/com/medcore/audit/AuditImmutabilityTest.kt`
- `docs/security/phi-exposure-review-3c.md` (this file)

Modified
- `apps/api/src/main/kotlin/com/medcore/identity/IdentityProvisioningService.kt`
- `apps/api/src/main/kotlin/com/medcore/platform/security/SecurityConfig.kt`
- `apps/api/src/main/kotlin/com/medcore/tenancy/api/TenantsController.kt`
- `apps/api/src/main/kotlin/com/medcore/tenancy/context/TenantContextFilter.kt`
- `apps/api/src/main/kotlin/com/medcore/tenancy/context/TenantContextFilterRegistration.kt`
- `apps/api/src/main/kotlin/com/medcore/tenancy/service/TenancyService.kt`
- `apps/api/src/test/kotlin/com/medcore/MedcoreApiApplicationTests.kt`
- `docs/runbooks/local-services.md`

**Summary.** Phase 3C lands ADR-003 Audit v1: a single
`audit.audit_event` Postgres table, a typed `AuditWriter` writing
synchronously inside the caller's transaction, and emission hooks at
the six ADR-003 sites (three identity actions, three tenancy actions).
DB-layer immutability via `medcore_app` role grants. No PHI module is
yet handled by Medcore at all, so no PHI surface is created in this
slice — only typed identity / tenancy event metadata is persisted to
audit.

**Modules affected:** `platform/audit`, `platform/security`,
`identity`, `tenancy`.

## 2. Data inventory

Every field that flows into `audit.audit_event` (the only new
persistence sink in this slice):

| Field           | Source                                          | Destination       | Classification | Tagged in schema? |
| --------------- | ----------------------------------------------- | ----------------- | -------------- | ----------------- |
| `id`            | `UUID.randomUUID()` in writer                   | `audit.audit_event` | Internal     | n/a (DB only)     |
| `recorded_at`   | injected `Clock` UTC                            | `audit.audit_event` | Internal     | n/a               |
| `tenant_id`     | already-internal tenant UUID                    | `audit.audit_event` | Internal     | n/a               |
| `actor_type`    | `ActorType` enum (USER/SYSTEM/SERVICE)          | `audit.audit_event` | Internal     | n/a               |
| `actor_id`      | `identity.user.id` (already-internal UUID)      | `audit.audit_event` | Internal     | n/a               |
| `actor_display` | NEVER POPULATED in 3C (always null)             | `audit.audit_event` | Internal     | n/a               |
| `action`        | `AuditAction.code` (closed enum, dotted token)  | `audit.audit_event` | Internal     | n/a               |
| `resource_type` | hard-coded literals (`"identity.user"`)         | `audit.audit_event` | Internal     | n/a               |
| `resource_id`   | UUID stringified (identity.user.id)             | `audit.audit_event` | Internal     | n/a               |
| `outcome`       | `AuditOutcome` enum (SUCCESS/DENIED/ERROR)      | `audit.audit_event` | Internal     | n/a               |
| `request_id`    | `X-Request-Id` header if present, else null     | `audit.audit_event` | Internal     | n/a               |
| `client_ip`     | `HttpServletRequest.remoteAddr`                 | `audit.audit_event` | Internal (PII-adjacent) | n/a    |
| `user_agent`    | `User-Agent` header                             | `audit.audit_event` | Internal     | n/a               |
| `reason`        | enum-derived short codes only (e.g. `not_a_member`, `via_header`, `count=N`, `invalid_bearer_token`) | `audit.audit_event` | Internal | n/a |

**No PHI field flows into audit.** Medcore has no PHI persistence
modules yet — the only PHI surface anticipated in the platform is
clinical data behind future modules under `apps/api` and
`packages/schemas/fhir/`, none of which exist in this slice.

PII-adjacent values (`client_ip`, `user_agent`) are persisted by
deliberate ADR-003 design (§2 column list). They never include user
identity attributes (email, display name, preferred username) — those
are extracted into `MedcorePrincipal` upstream and explicitly NOT
plumbed into `AuditEventCommand`.

## 3. Exposure checklist

- [x] **No** — Does this change write a PHI field to a structured log?
      No PHI fields exist in the change.
- [x] **No** — Does it write PHI to an error message or exception?
      Exception messages in the writer cite the SQL state text only;
      `TenantAccessDeniedException.message` carries `userId=<uuid>
      slug=<...> reason=<code>` and is server-side-only (never echoed
      to the client; `TenancyExceptionHandler` returns a uniform
      "Access to the requested tenant is denied." body).
- [x] **No** — Does it include PHI in a metric label, span attribute, or
      trace event? No metrics or traces added in this slice.
- [x] **No** — Does it send PHI to an analytics, session-replay, or
      telemetry sink? None added.
- [x] **No** — Does it embed PHI in a URL, query string, or path
      parameter? Tenant slug is in the path of `/api/v1/tenants/{slug}/me`
      (existing 3B.1 surface, unchanged); slug is not PHI.
- [x] **No** — Does it persist PHI to localStorage, sessionStorage,
      IndexedDB, a cookie, or a non-encrypted cache? No client storage
      changes; backend-only slice.
- [x] **No** — Does it include PHI in an email, webhook, or outbound
      integration? No outbound integrations added.
- [x] **No** — Does it include PHI in a build artifact, snapshot, or
      generated file? No.
- [x] **No** — Does it pass PHI into an AI prompt, embedding call, or
      model log? No AI integrations.
- [x] **No** — Does it expand the set of roles / tenants that can read
      this data? Audit rows are read by `medcore_app` with `SELECT`
      grant; no PHI exists in those rows.
- [x] **No** — Does it export PHI to a file, CSV, PDF, or download? No.
- [x] **No** — Does it appear in a test fixture, seed, or README example?
      Test tokens use synthetic strings (`audit-<UUID>` subjects,
      `<subject>@medcore.test` emails); no real or realistic PHI.
- [x] **Verified** — `AuditTenancyIntegrationTest.audit rows never
      persist slug or tenant display name` and `AuditIdentityIntegrationTest.audit
      rows never persist user email or display name` actively assert
      these strings do NOT appear in any persisted audit row, for every
      action emitted in this slice.

## 4. Redaction verification

- The audit pipeline does not use the structured logger; it writes
  directly to `audit.audit_event` via JDBC. No log redaction layer is
  involved.
- `AuditEventCommand` is a flat data class with typed enum fields and
  short string identifiers. There is no `Map<String, Any>` bag; no
  reflective serialization of arbitrary objects.
- The writer never serializes the JWT or any header other than
  `User-Agent` and `X-Request-Id`. `Authorization` is explicitly NOT
  read by `RequestMetadataProvider`.

## 5. Access-control verification

- `audit.audit_event` is granted `INSERT, SELECT` to `medcore_app`;
  `UPDATE, DELETE, TRUNCATE` are explicitly revoked. Verified by
  `AuditImmutabilityTest` connecting as `medcore_app` and asserting
  permission-denied SQL errors on every forbidden op.
- All `/api/**` routes still require an authenticated principal
  (Spring Security chain unchanged). The new
  `AuditingAuthenticationEntryPoint` only adds an audit emission on
  invalid-bearer 401s; it does not loosen any authentication check.

## 6. AI-specific checks

Not applicable; no AI integration in this slice.

## 7. Tests

Direct anti-leakage assertions:

- `AuditIdentityIntegrationTest.audit rows never persist user email or
  display name` — sweeps every audit row's string columns for the
  caller's email, display name, preferred username, and subject.
- `AuditTenancyIntegrationTest.audit rows never persist slug or tenant
  display name` — sweeps every audit row's string columns for the
  tenant slug and display name across both filter-driven and
  controller-driven flows.

Behavioural coverage of the audit pipeline:

- 5 identity tests covering provisioned, login.success, repeat
  login.success, login.failure, and the
  no-token-no-failure-event policy.
- 8 tenancy tests covering list, header-success, header-denied (unknown
  slug / inactive states), path-driven denial, denial-distinction
  reason codes, slug-unknown vs not-a-member, and the policy that
  per-slug `/me` success does NOT emit `tenancy.context.set`.
- 5 immutability tests covering INSERT, SELECT, UPDATE-denied,
  DELETE-denied, TRUNCATE-denied at the DB layer as the
  `medcore_app` role.

## 8. Risk summary

**None.** No PHI surface exists in Medcore today, no PHI flows into
`AuditEventCommand` by construction, and tests actively prove that
neither identity PII nor tenant identifying strings leak into the
audit table. The PII-adjacent fields persisted (`client_ip`,
`user_agent`) are exactly what ADR-003 §2 specifies.

## 9. Sign-off

- Author (assistant): review performed on the final 3C diff in the
  current session, prior to commit.
- Reviewer: requires human review against the diff before commit; this
  artifact is the input to that review.
