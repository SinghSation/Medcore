# Medcore — Project Timeline

> A chronological narrative of every commit landed on `main`. Compiled
> originally from the git log of `origin/main`, the working tree, and
> the session record; extended append-only as new slices land. Factual
> state (commit SHA, status, counts) is corrected in place; narrative
> content is never rewritten. Intended as the single reference for
> "how did we get here, and why".

---

## At a glance

| Phase | Date | Commit | Subject | Files / Lines |
| ---- | ---- | ---- | ---- | ---- |
| 0 | 2026-04-20 | `39fae2d` | Initial commit: add `.gitignore` | 1 / +38 |
| 0 | 2026-04-20 | `f243e6f` | Initialize Phase 0 governance foundation | 34 / +2,530 / −17 |
| 0 | 2026-04-20 | `ddc55df` | Add GitHub auth runbook for macOS | 1 / +178 |
| 0 | 2026-04-20 | `b1959fe` | Scaffold backend + frontend platform shells | 29 / +2,759 / −8 |
| 3 (ADRs) | 2026-04-20 | `9b8dea4` | Add ADR-001, ADR-002, ADR-003 | 3 / +659 |
| 3A.1 | 2026-04-20 | `fa3c316` | Add Postgres compose + runbook | 3 / +250 |
| 3A.2 | 2026-04-21 | `93f44c5` | JPA + Flyway + Postgres substrate | 5 / +58 |
| 3A.2 follow-up | 2026-04-21 | `a06ee43` | Testcontainers isolation + Flyway state assertions | 2 / +78 / −2 |
| 3A.3 | 2026-04-21 | `32b635f` | Identity foundation — OIDC, `/me`, JIT provisioning | 27 / +1,384 / −17 |
| 3B.1 | 2026-04-21 | `6706824` | Tenancy foundation — tenant + membership read surface | 22 / +1,656 |
| 3C | 2026-04-21 | `2e89c1e` | Audit v1 — append-only, synchronous, immutable | 21 / +1,934 / −80 |
| Governance | 2026-04-21 | `14dfc84` | ADR-004 — tiered execution authority model | 4 / +775 / −174 |
| 3D | 2026-04-21 | `24e5e43` | RLS substrate + Audit v2 chain | 13 / +1,716 / −73 |
| 3E | 2026-04-21 | `e690275` | Runtime datasource role switch + verify-only password | 19 / +994 / −25 |
| 3F (rolled up) | 2026-04-21..22 | `3da3ff8`..`217eece` | Observability spine — request-id + MDC + actuator + error envelope + OTLP + audit chain verify | (rolled up) |
| 3H | 2026-04-22 | `ce82724` | Secrets + production-posture substrate | (rolled up) |
| 3J (rolled up) | 2026-04-22 | `04ff076`..`a401e50` | WriteGate substrate + tenancy writes + RBAC + membership invite + role update + revoke | (rolled up) |
| 3I.1 / 3I.2 | 2026-04-22..23 | `30241ca` / `3d85d05` | ArchUnit dependency rules + CI gates (test + governance + secret-scan) | (rolled up) |
| 3K.1 | 2026-04-23 | `3613c1f` | ClaimsNormalizer + WorkOS broker posture (ADR-008) | (rolled up) |
| 4A.0 | 2026-04-23 | `0b865ee` | PHI RLS substrate (prerequisite for clinical) | (rolled up) |
| 4A.1..4A.5 (rolled up) | 2026-04-23 | `c579f5f`..`7b5a4f6` | Patient schema + writes + identifiers + read + FHIR R4 shape | (rolled up) |
| Stabilization | 2026-04-23 | `bde9ab3` | Wire `@Primary` + `@FlywayDataSource` DataSource pair | (rolled up) |
| **VS1 Chunk A** | 2026-04-23 | `6857759` | Frontend scaffolding + demo login | 29 / +1,841 / −424 |
| 4B.1 | 2026-04-23 | `5ce4e7d` | List patients endpoint + `CLINICAL_PATIENT_LIST_ACCESSED` audit | 14 / +1,590 / −16 |
| **VS1 Chunk B** | 2026-04-23 | `d1e381f` | Patient list page wired to tenant home | 6 / +545 / −13 |
| Governance | 2026-04-23 | `da414b3` + `12ee0c5` + `6d12995` | Rule 07 + §3.7 naming rule pointer + Chunks A/B tier-skip incident note + runbook pin | (rolled up) |
| **VS1 Chunk C** | 2026-04-23 | `28a6817` | Patient detail page + `data-phi` container tagging | 6 / +643 / −4 |
| **VS1 Chunk D (backend)** | 2026-04-23 | `75e6ac2` | Encounter shell — V18 schema + start + get + `ENCOUNTER_*` authorities (4C.1) | 25 / +1,904 / −3 |
| **VS1 Chunk D (frontend)** | 2026-04-23 | `b1ff0c3` | Start-encounter button + encounter detail page (4C.2) | 6 / +561 / −1 |
| **VS1 Chunk E (backend)** | 2026-04-23 | `c45e16a` | Encounter notes — V19 schema + create + list + `NOTE_*` authorities (4D.1) | 27 / +2,092 / −6 |
| **VS1 Chunk E (frontend)** | 2026-04-23 | `13007d9` | Note editor + list on encounter detail page (4D.2) | 4 / +541 / −29 |
| **VS1 Chunk F** | 2026-04-24 | `1dc34ef` | Playwright E2E + DoD + PHI leakage scan (4D.3) | 14 / +985 / −2 |
| **VS1 Chunk G** | 2026-04-24 | (this commit) | CI `web` + `e2e` jobs + VS1 close-out governance (4D.4) | (this commit) |

Total committed through VS1 Chunk F: **50 commits**, all on `main`,
all pushed to `origin/main`. VS1 Chunk G lands with this slice.

> **Narrative gap note.** Phases 3F through 4B.1 are captured in
> the "At a glance" table above as rolled-up rows. Per-phase
> narrative sections (matching the detailed treatment Phases 0–3E
> received) were skipped during landing; backfilling them is a
> tracked carry-forward. The commit history on `main` is the
> canonical record for those phases; the review pack at
> `docs/evidence/vs1-review-pack.md` covers VS1 itself in detail.

---

## Phase 0 — Governance and shells (2026-04-20)

### Commit `39fae2d` — `Initial commit: add .gitignore`

The repository's first commit. Establishes a hardened `.gitignore` covering OS junk, IDE artifacts, JVM / Node / Go / Python / Terraform build output, secrets / credentials / certificates / private keys, `.env` files, PHI patterns (`*.phi`, `patients/`, `*.hl7`, `*.ccda`, `*.ccd`, `*.ccr`), local DB dumps, and logs. Wrapper jars at `**/gradle/wrapper/gradle-wrapper.jar` are explicitly *un-ignored* so Gradle can self-bootstrap on a fresh clone.

### Commit `f243e6f` — `governance(repo): initialize Medcore Phase 0 governance foundation`

The biggest single shape-establishing commit. Lands every governance artifact the project has been operating under since:

- **`AGENTS.md`** — the operating contract for every AI/human agent. Defines product context (HIPAA, HITECH, FHIR R4, SOC 2, 21 CFR Part 11, GDPR), repository structure (frozen), non-negotiable invariants (PHI handling, secrets, crypto, auth, authz, input validation, outbound calls, AI), agent operating procedures, and the original §4.7 / §4.8 phrase-based commit/push regime.
- **`docs/architecture/medcore_architecture_charter.md`** — adopts the non-negotiable principles (safety first, privacy by construction, contract-first, interoperability by default, observability is not optional, reproducible infrastructure, least privilege, reversibility), names the architectural style (modular monolith with strict internal boundaries), defines data classification, and sets the change-control surface.
- **`.cursor/rules/00-06`** — IDE enforcement rules: global architecture, security invariants, API contracts, DB migrations, frontend standards, testing policy, audit/observability.
- **`.claude/skills/`** — five reusable procedures: `api-contract-first`, `new-module`, `phi-exposure-review`, `safe-db-migration`, `safe-local-commit`.
- **`docs/adr/000-template.md`** — the ADR template all subsequent decisions copy from.
- **`Makefile`** + **`README.md`** + **`.editorconfig`** — developer entrypoints. `make verify` chains formatters, linters, type checks, tests, and the secrets scan.
- Placeholder `.gitkeep` files for the frozen directory structure.

### Commit `ddc55df` — `docs(runbooks): add GitHub auth runbook for macOS workstations`

Operator runbook for setting up `gh` CLI authentication. First doc under `docs/runbooks/`.

### Commit `b1959fe` — `build(bootstrap): scaffold backend and frontend platform shells`

The first commit with executable code. Lands:

- **`apps/api/`** — Kotlin + Spring Boot 3.4 + JDK 21. `build.gradle.kts` declares `spring-boot-starter-web`, the Kotlin Spring/JPA plugins, and basic test deps. The Gradle wrapper is committed (8.11.1). Single Spring Boot main class, default `application.yaml`, one trivial application-context test.
- **`apps/web/`** — React 18 + Vite + Vitest + happy-dom + TypeScript. Strict `tsconfig`, Vite config with `strictPort: true`, one trivial component + one Vitest test.
- **`pnpm-workspace.yaml`** + root `package.json` + lockfile — pnpm-based monorepo for the frontend.
- **`Makefile`** gains `api-dev`, `api-test`, `api-build`, `web-dev`, `web-test`, `web-typecheck`, `web-build` targets.
- **`docs/runbooks/development.md`** — first-time developer setup runbook.

This commit also exercised AGENTS.md's dependency-change high-risk gate: the human approved both the generic commit gate and the per-change dependency gate in the same session.

---

## Phase 3 ADRs — direction-setting (2026-04-20)

### Commit `9b8dea4` — `governance(adr): add ADR-001, ADR-002, ADR-003 for Phase 3 foundation`

Three ADRs that scoped the entire next ten commits.

- **ADR-001** — *Adopt PostgreSQL + Flyway with row-level multi-tenancy by `tenant_id`; RLS deferred to Phase 3D.* Postgres 16 as the relational store, Flyway as the migration tool, one schema per module (`identity`, `tenancy`, `audit`), every tenant-scoped table carries `tenant_id NOT NULL`, no cross-module foreign keys (IDs only), `ddl-auto=validate`. RLS is designed into the schema from day one but enforced only from Phase 3D — a hard gate before any PHI-bearing work.
- **ADR-002** — *Authentication is OIDC-only; local dev uses `mock-oauth2-server`; production IdP choice deferred.* Spring Security resource-server, JWT validation via JWKS, JIT-provisioned user rows keyed on `(issuer, subject)`, `MedcoreOidcProperties` typed config, prod-profile guardrail rejecting `mock-oauth2-server`/`localhost` issuers. No "auth disabled in dev" switch.
- **ADR-003** — *Audit v1 is append-only, synchronous, immutable by DB grants; cryptographic integrity (v2) deferred to Phase 3D.* `audit.audit_event` table with a fixed v1 column set, `medcore_app` granted `INSERT, SELECT` only (UPDATE/DELETE/TRUNCATE revoked), strongly typed `AuditEventCommand` (no free-form bag), synchronous writes inside the caller's transaction.

These three ADRs are the lens every subsequent slice was reviewed against.

---

## Phase 3A.1 — Local Postgres (2026-04-20)

### Commit `fa3c316` — `chore(local-services): add postgres compose and runbook`

`docker-compose.yml` defines `postgres:16.4` with a healthcheck and `PGTZ=UTC`. `docs/runbooks/local-services.md` documents start/stop/wipe procedures and `psql` access. `.env`-required pattern: no default password.

---

## Phase 3A.2 — Persistence substrate (2026-04-21)

### Commit `93f44c5` — `feat(persistence): add JPA, Flyway, and Postgres substrate for Phase 3A`

Wires the application up to Postgres without yet introducing any business tables.

- `apps/api/build.gradle.kts` adds `spring-boot-starter-data-jpa`, `flyway-core`, `flyway-database-postgresql`, `postgresql`, `spring-boot-testcontainers`, `testcontainers:postgresql`, `testcontainers:junit-jupiter`.
- `application.yaml` configures the datasource, Hibernate `ddl-auto=validate`, `open-in-view: false`, UTC `jdbc.time_zone`, Flyway with the three module locations and four schemas (`flyway`, `identity`, `tenancy`, `audit`).
- Three baseline migrations (`V1__identity_baseline.sql`, `V2__tenancy_baseline.sql`, `V3__audit_baseline.sql`) — empty markers per ADR-001 §6, so Flyway's history is non-empty before any real DDL lands.

### Commit `a06ee43` — `test(api): isolate tests with Testcontainers and assert Flyway state`

Tightening pass landed as its own commit. `TestcontainersConfiguration` provides a `PostgreSQLContainer` via `@ServiceConnection`. The application-context test is augmented with two new assertions:
- The four expected schemas (`flyway`, `identity`, `tenancy`, `audit`) exist after Flyway runs.
- The Flyway history table contains the three baseline migrations in order, all successful.

This established the pattern of **"every Flyway change requires a corresponding history-table assertion update"** — followed by every subsequent migration commit.

---

## Phase 3A.3 — Identity foundation (2026-04-21, `32b635f`)

The first authenticated backend slice. Iterations during this session:

1. **Initial implementation** — typed `MedcoreOidcProperties` (with `@Validated` + `@field:NotBlank`), `ProdProfileOidcGuard` registered via `META-INF/spring.factories` (fires on `ApplicationEnvironmentPreparedEvent`), `SecurityConfig` wiring Spring Security resource-server over `/api/**` with the OIDC `JwtDecoder` discovered from the configured issuer + an optional `AudienceValidator`. `MedcorePrincipal` (initially a Kotlin `data class`) carrying the resolved internal `userId`, the external `(issuer, subject)`, and the raw `Jwt`. `MedcoreJwtAuthenticationConverter` turning a validated JWT into `MedcoreAuthenticationToken`. `IdentityProvisioningService` JIT-provisioning rows in `identity."user"` keyed on `(issuer, subject)` (idempotent find-or-insert). `IdentityUserEntity` as a mutable JPA class (not a data class — explicit project rule). `MeController` returning a DTO. OpenAPI contract at `packages/schemas/openapi/identity/identity.yaml` plus shared `errors/error-envelope.yaml`. Flyway `V4__identity_user.sql`. Integration tests covering 401-without-token, 200-with-JIT-provisioning, idempotency, and exact response shape. Plus a unit suite for `ProdProfileOidcGuard` covering localhost/mock/loopback rejections under the prod profile.
2. **Tightening pass before commit** — three KDoc fixes (`PrincipalResolver` mentioned a non-existent implementor; `MeResponse` KDoc said `NON_NULL` but annotation was `ALWAYS`; `IdentityUserEntity` KDoc claimed `created_by`/`updated_by` shipped — they didn't). Hardened `MedcorePrincipal`: dropped `data class` (unsafe `equals`/`hashCode`/`toString` with raw `Jwt` + PII fields), removed the `Jwt` field entirely, added explicit `equals`/`hashCode` keyed on `userId`, and a PII-free `toString`. Tightened `MedcoreAuthenticationToken.getCredentials()` to return a redacted sentinel rather than the bearer token. `PrincipalResolutionCommand` lost its `Jwt` field too — converter now extracts `issuedAt`/`expiresAt` upstream so the token never crosses the platform → feature boundary. Introduced `PrincipalResolver` SPI in `platform/security/` so the security layer doesn't import identity persistence (Charter §4 / Rule 00). All 13 tests preserved through the refactor (3 baseline + 4 `MeEndpointIntegrationTest` + 6 `ProdProfileOidcGuardTest`).

Committed under §4.7 high-risk approval naming authentication/security and database-migration. Pushed to `origin/main`.

Carry-forward registered: audit hooks (Phase 3C), tenancy (Phase 3B), concurrent-first-login retry, role/authority mapping, generated `api-client`, production IdP ADR.

---

## Phase 3B.1 — Tenancy foundation (2026-04-21, `6706824`)

### Initial implementation

- **Migrations** — `V5__tenant.sql` and `V6__tenant_membership.sql`. `tenant_id` FK is same-module; `tenant_membership.user_id` is an *opaque UUID* — no cross-module FK to `identity.user.id` (ADR-001 §2). Slug has a lower-kebab `CHECK` constraint. Status enums via `CHECK` (`ACTIVE/SUSPENDED/ARCHIVED` for tenant; `ACTIVE/SUSPENDED/REVOKED` for membership). Indexes for both lookup directions.
- **Module layout** — `tenancy/domain` (initial location of enums), `tenancy/persistence`, `tenancy/service`, `tenancy/context`, `tenancy/api`. Mutable JPA classes; `@Version` for optimistic concurrency; `@Transactional(readOnly = true)` on the service.
- **Routes** — `GET /api/v1/tenants` returns the caller's ACTIVE memberships on ACTIVE tenants, sorted by slug. `GET /api/v1/tenants/{slug}/me` returns details of the caller's membership.
- **Tenant context** — `TenantContext` request-scoped bean (`SCOPE_REQUEST`, `TARGET_CLASS` proxy). `TenantContextFilter` reads optional `X-Medcore-Tenant`, validates membership via the new `TenantMembershipLookup` SPI in `platform/tenancy/`, populates `TenantContext` on success, returns a uniform `403 tenancy.forbidden` envelope on any failure (no enumeration). Filter registered after Spring Security via `FilterRegistrationBean` at `SecurityProperties.DEFAULT_FILTER_ORDER + 10`. `TenantContextFilter` is *not* a `@Component` — to avoid Spring Boot's auto-registration duplicating it. Belt-and-braces: extends `OncePerRequestFilter`.
- **OpenAPI** — `packages/schemas/openapi/tenancy/tenancy.yaml`, references the shared error envelope.
- **PHI review** — none authored at this stage.

### First polish pass (before commit)

The review pack for 3B.1 flagged a real architectural concern: the platform-side port `TenantMembershipLookup` was importing from `tenancy.domain.*` for the enum types, breaking the strict one-way `tenancy → platform` dependency. Fixed by **moving the three enums (`TenantStatus`, `MembershipStatus`, `MembershipRole`) into `platform/tenancy/`** alongside the port, deleting the `tenancy/domain/` package, and updating tenancy code to import from `platform.tenancy.*` instead. Also added: typed `TenantContextMissingException` (replacing a generic `error(...)` in `TenantContext.require`), strengthened `ErrorResponse.details` KDoc to forbid free-form usage, runbook section for local tenancy exploration with concrete `psql` + `curl` examples, and a `TenantContextFilterRegistrationTest` proving the filter is wired exactly once at the expected order and extends `OncePerRequestFilter`.

Tests: 31 (3 prior + 13 from 3A.3 minus the now-shared 3 = re-counted: 3 `MedcoreApiApplicationTests` + 4 `MeEndpointIntegrationTest` + 6 `ProdProfileOidcGuardTest` + 15 `TenantsEndpointIntegrationTest` + 3 `TenantContextFilterRegistrationTest` = 31).

Committed under §4.7 high-risk approval naming authentication/security and database-migration. Pushed.

Carry-forward extended: audit hooks (Phase 3C), RLS (Phase 3D), tenancy writes/admin, role-derived authorities, generated api-client, production IdP, uniform 401 envelope, `TenantContextMissingException` HTTP mapping, concurrent first-login retry.

---

## Phase 3C — Audit v1 (2026-04-21, `2e89c1e`)

### A near-miss before the real implementation

The first 3C prompt proposed a no-op `AuditService` with a fire-and-forget posture — directly conflicting with ADR-003 §2 ("synchronous writes inside the caller's transaction") and Rule 06 ("fire-and-forget audit is prohibited"). The prompt was flagged as a governance conflict per AGENTS.md §4.1 ("if a user instruction conflicts with this file, the agent MUST stop, flag the conflict, and request explicit human confirmation"), and a corrected master prompt was issued that aligned with ADR-003.

### Implementation

- **Migration `V7__audit_event.sql`** — full ADR-003 §2 column set on `audit.audit_event` (`id`, `recorded_at`, `tenant_id`, `actor_type`, `actor_id`, `actor_display`, `action`, `resource_type`, `resource_id`, `outcome`, `request_id`, `client_ip`, `user_agent`, `reason`). CHECK constraints pin `actor_type` to `{USER, SYSTEM, SERVICE}` and `outcome` to `{SUCCESS, DENIED, ERROR}`. BRIN index on `recorded_at`, B-tree on `(tenant_id, recorded_at)`. The migration also creates the `medcore_app` Postgres role idempotently (LOGIN, NOINHERIT, no password — ops sets password out-of-band), grants `INSERT, SELECT` on `audit.audit_event`, explicitly `REVOKE`s `UPDATE, DELETE, TRUNCATE`.
- **Platform audit types** — `AuditAction` (closed enum of the six ADR-003 §7 dotted action codes), `ActorType`, `AuditOutcome`, `AuditEventCommand` (flat typed data class — no `Map<String, Any>` bag), `AuditWriter` interface. `JdbcAuditWriter` uses `JdbcTemplate` + `TransactionTemplate(PROPAGATION_REQUIRED)` — joins the caller's transaction when one exists, starts a fresh one otherwise. Exceptions propagate (ADR-003 §2: failure to write audit fails the audited action). `RequestMetadataProvider` lifts `X-Request-Id` / `client_ip` / `user_agent` from `RequestContextHolder` so callers don't touch HTTP internals.
- **Identity instrumentation** — `IdentityProvisioningService` emits `identity.user.provisioned` on first-time JIT insert and `identity.user.login.success` on every successful resolution, atomic with the `identity.user` write.
- **Identity login failure** — `AuditingAuthenticationEntryPoint` wraps Spring Security's default bearer entry point and emits `identity.user.login.failure` *only when an `Authorization: Bearer …` header was actually present* (anonymous probes stay silent — no noise).
- **Tenancy instrumentation** — `TenancyService.findMembershipBySlug` was renamed to `findMembershipForCallerBySlug` and refactored to return a sealed `TenantMembershipResult` (`Granted | Denied`); each denial branch emits `tenancy.membership.denied` with a distinct coarse reason code (`slug_unknown`, `not_a_member`, `membership_inactive`, `tenant_inactive`). `listMembershipsFor` emits `tenancy.membership.list` with a `count=N` reason. `TenantContextFilter` emits `tenancy.context.set` on header-resolve success and `tenancy.membership.denied` on header failure with header-specific reason codes. **Controllers emit no audit** — the prompt's explicit boundary.
- **`TenancyService.@Transactional(readOnly = true)`** dropped from methods that now write audit, since they're no longer purely read-only — preserves ADR-003 §2 atomicity.
- **Tests** — `AuditIdentityIntegrationTest` (5), `AuditTenancyIntegrationTest` (8), `AuditImmutabilityTest` (5 — connects directly as `medcore_app` and asserts `UPDATE`/`DELETE`/`TRUNCATE` fail with `permission denied`).
- **PHI exposure review** — `docs/security/phi-exposure-review-3c.md`. Risk: None.

### Tightening pass — atomicity proof

ChatGPT-mediated review flagged that the writer's transaction-join behavior was *claimed* but not *proven*. Added `AuditTransactionAtomicityTest` which:
- Replaces `JdbcAuditWriter` with a `PoisonableAuditWriter` via `@TestConfiguration` + `@Primary`.
- Arms the writer to throw on the next call.
- Calls `/api/v1/me` to drive the JIT-provisioning path.
- Asserts: the response is NOT 200, `identity.user` has zero rows for the subject, and `audit.audit_event` is empty.

Result: 49 tests across 8 suites + 1 atomicity test = 50 green. Also tightened: `V7` header (explicit idempotency rationale), `JdbcAuditWriter` KDoc (load-bearing transaction-join semantics now documented), runbook §11 (explicit deferral of the runtime app-role switch — not paper over).

Committed under §4.7 high-risk approval. Pushed.

Carry-forward at this point included: **runtime datasource role switch to `medcore_app`** elevated to top priority (because immutability was tested via direct DB connection but the running app was still superuser).

---

## Governance — ADR-004 tier model (2026-04-21, `14dfc84`)

### Origin

After 3C, the user proposed a "standing approval" pattern that would let the agent commit + push autonomously by default unless an explicit "hold" was given. That pattern conflicted with `AGENTS.md` §§4.7/4.8 (single-use phrase-based approval) and §6 (precedence). Per AGENTS.md §4.1, the conflict was flagged rather than silently adopted.

The compliant path: amend §§4.7/4.8 via a properly-governed ADR before the new workflow becomes active.

### What ADR-004 introduced

A **three-tier execution authority model**:

- **Tier 3 (high-risk)** — preserves the existing phrase regime verbatim. Authentication, authorization, audit logging, non-additive migrations, infra/Terraform, dependency manifests with material transitive changes, secrets, PHI, FHIR contracts, and governance files (`AGENTS.md`, `.cursor/rules/**`, `.claude/**`, `docs/adr/**`, `docs/architecture/**`) all require the exact phrase `"approved to commit"` plus a per-change approval naming the high-risk area, plus `"approved to push"` before any push. Single-use, scoped to the preceding turn.
- **Tier 2 (medium-risk)** — purely-additive Flyway migrations (strict allow/deny list — `CREATE TABLE` of a new table; `ADD COLUMN` NULL with no default; `CREATE INDEX`; role/grant expansions; `CREATE SCHEMA`/`CREATE TYPE`; everything else forces Tier 3), new non-FHIR OpenAPI contracts, new endpoints/controllers/services outside Tier 3 areas, production-posture-affecting test infrastructure, security-adjacent runbooks. Autonomous commit; `"approved to push"` still required.
- **Tier 1 (safe)** — everything else. Autonomous commit AND push.

Plus **universal preconditions** (scope complete, files shown, validations run, no forbidden content, `Tier: N` line + carry-forward in commit body), **universal guardrails** (failing tests, ADR/rule conflict, forbidden content, scope overrun, unflagged Tier 3 area, classification ambiguity, unclear migration safety, hook failure — any one forces stop-and-flag), **override phrases** (`hold`, `review only`, `draft only`, `do not commit`, `do not push`), and the **highest-tier-wins tie-breaker** (added in the polish pass after ChatGPT review): mixed-tier or ambiguous slices get the highest tier touched.

The slice itself was Tier 3 (governance files) under the *prior* regime — committed with the standard phrase + per-area naming, then pushed. From the commit *after* ADR-004, the tier model is live.

Files: 4 (`docs/adr/004-tiered-execution-authority-model.md` new, `AGENTS.md` §§4.7–4.8 restructured, `.cursor/rules/00-global-architecture.mdc` git-authority section rewritten, `.claude/skills/safe-local-commit.md` rewritten as a 10-step tier-aware procedure).

---

## Phase 3D — RLS substrate + Audit v2 (2026-04-21, `24e5e43`)

First slice under the new tier model. **Tier 3** by multiple independent triggers (non-additive migrations + audit + authorization-adjacent platform behavior).

### Initial implementation

- **`V8__tenancy_rls.sql`** — `ENABLE` + `FORCE ROW LEVEL SECURITY` on both `tenancy.tenant` and `tenancy.tenant_membership` (no partial coverage). Two policies keyed on `app.current_user_id`:
  - `p_tenant_select_by_active_membership` — caller sees a tenant only if they have an `ACTIVE` membership for it.
  - `p_membership_select_own` — caller sees only their own membership rows.
  Both `USING` clauses use `NULLIF(current_setting('app.current_user_id', true), '')::uuid` so a missing/empty GUC reads as `NULL` — the row filter fails closed. `medcore_app` granted `USAGE` on `tenancy` schema + `SELECT` on both tables. No `INSERT/UPDATE/DELETE` policies (default-deny; admin surface deferred). `identity.user` and `audit.audit_event` are intentionally out of RLS scope (ADR-001 §2 calls identity cross-tenant infrastructure; audit is governed by V7's grant model + V9's hash chain).
- **`V9__audit_event_v2.sql`** — adds `sequence_no BIGINT NOT NULL` (after backfill), `prev_hash BYTEA` (NULL only for sequence 1), `row_hash BYTEA NOT NULL`. Five SQL functions:
  - `audit.canonicalize_event(...)` — deterministic pipe-delimited string form (UTC microsecond timestamps via `to_char(... AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.USOF')`, `host()` for `INET`, empty strings for `NULL`, hex-encoded `prev_hash` for byte stability).
  - `audit.compute_chain_hash(canonical, prev_hash)` — `sha256(convert_to(canonical || encode(prev_hash, 'hex'), 'UTF8'))`.
  - `audit.append_event(...)` — the new write path. Acquires `pg_advisory_xact_lock(hashtext('audit.audit_event.chain'))`, derives the next `sequence_no` from the chain tip, computes `row_hash`, INSERTs.
  - `audit.rebuild_chain()` — recomputes the whole chain; requires `UPDATE` (migrator/superuser only). Used by V9's backfill and as an ops recovery tool.
  - `audit.verify_chain()` — walks the chain, returns one row per break with a coded `reason` (`sequence_no_null`, `sequence_gap`, `first_row_has_prev_hash`, `prev_hash_mismatch`, `row_hash_mismatch`). Returns zero rows for a healthy chain.
  Backfill calls `rebuild_chain()` in the migration. NOT NULL is enforced after the backfill. `UNIQUE` index on `sequence_no`. `medcore_app` granted `EXECUTE` on `append_event` and `verify_chain`; *not* on `rebuild_chain`.
- **`TenancySessionContext`** — Spring component using `SELECT set_config(name, value, true)` (= `SET LOCAL`) so Postgres auto-reverts at commit/rollback (no GUC leakage across pooled connections). Guarded by `TransactionSynchronizationManager.isActualTransactionActive()` — calling outside a tx throws.
- **`JdbcAuditWriter` refactor** — switched from raw `INSERT` to `SELECT audit.append_event(...)` via `JdbcTemplate.execute(PreparedStatementCallback)` (the PostgreSQL JDBC driver rejects `executeUpdate()` on `SELECT`). Transactional join semantics preserved verbatim.
- **`TenancyService` wiring** — three lines: each `@Transactional` method now calls `sessionContext.apply(userId, tenantId = null)` first.
- **Tests** — `TenancyRlsTest` (5), `TenancyGucLifecycleTest` (4), `AuditV2ChainTest` (6), `AuditV2BackfillTest` (1). RLS tests connect directly as `medcore_app` (with a local-only password set in test setup) so policy evaluation is real (the default container superuser bypasses RLS).

### Re-scope after review

The first review pack overstated 3D as "the RLS hard gate". The user pointed out that since the running app still connects as the container superuser locally, RLS is bypassed at runtime — the policies are correct but enforcement isn't yet effective for live traffic.

**Re-scoped before commit**: 3D ships the *RLS substrate + Audit v2*, NOT the runtime hard gate. Three places now carry the explicit clarification:
- V8 migration header opens with "Scope of this migration" + "Scope NOT included" sections.
- `docs/runbooks/local-services.md` §12 opens with a blockquote: *"RLS correctness is proven against `medcore_app`, but runtime enforcement for the running application remains dependent on the deferred datasource-role switch."*
- PHI exposure review §5 names the deferred ops slice as the gating prerequisite.

Also corrected a file-count miscount in the review pack (8 new + 5 modified = 13, not the originally-stated 11).

Committed under Tier 3 approval. Pushed. **Runtime datasource role switch elevated to top-priority carry-forward.**

---

## Phase 3E — Runtime datasource role switch (2026-04-21, `e690275`)

Tier 3 by multiple independent triggers (auth-adjacent platform behavior + non-additive migrations + secrets handling).

### What 3E ships

- **`V10__runtime_role_grants.sql`** — `GRANT USAGE` on `identity` schema + `SELECT, INSERT, UPDATE` on `identity."user"` (JIT path needs all three). Explicit `REVOKE DELETE, TRUNCATE` on `identity."user"`. Tenancy DML and role passwords are *not* set in this migration — credential handling is an ops concern.
- **`application.yaml` split** — `spring.datasource.*` connects as `medcore_app` (env-supplied password); `spring.flyway.*` has its own URL/user/password defaulting to the legacy `POSTGRES_USER`/`POSTGRES_PASSWORD` for the local migrator. New `medcore.db.app.password` and `medcore.db.app.passwordSyncEnabled` properties.
- **`MedcoreAppPasswordSync`** — Spring component, `@DependsOn("flywayInitializer")`. Two execution modes controlled by `medcore.db.app.passwordSyncEnabled`:
  - `false` (production-default) — VERIFY-ONLY. Asserts `MEDCORE_DB_APP_PASSWORD` is non-blank and stops. Does NOT call `ALTER ROLE`.
  - `true` (local/test opt-in) — SYNC. Calls `ALTER ROLE medcore_app WITH PASSWORD …` against the migrator datasource via a parameter-bound `pg_temp` function (no SQL string interpolation of secret material).
- **`JpaDependsOnPasswordCheck`** — `EntityManagerFactoryDependsOnPostProcessor("medcoreAppPasswordSync")` so JPA's first connection waits for verification (and sync, when enabled) to complete.
- **`DatabaseRoleSafetyCheck`** — `@EventListener(ApplicationStartedEvent::class)`. Asserts `current_user` is non-superuser; throws `REFUSING TO START` (citing ADR-001 §2) and refuses to serve traffic if connected as a superuser.
- **`TestcontainersConfiguration` restructure** — three explicit `@Bean` `DataSource`s:
  - `appDataSource` (`@Primary`, `medcore_app`, HikariCP).
  - `flywayDataSource` (`@FlywayDataSource`, container superuser, HikariCP).
  - `adminDataSource` (qualified `"adminDataSource"`, container superuser, `DriverManagerDataSource`) — for fixtures `medcore_app` cannot perform.
  `System.setProperty` in companion init for timing-robust property visibility (`medcore.db.app.password` AND `medcore.db.app.passwordSyncEnabled=true` for tests).
- **12 existing test files refactored** — `@Qualifier("adminDataSource")` on cleanup/seeding paths so they bypass `medcore_app`'s grant restrictions.
- **`AuditTenancyIntegrationTest`** — two tests adapted to the *correct* RLS-induced behavior change: with runtime RLS live, alice (a non-member of `beta-clinic`) can no longer see the tenant row at all, so the service collapses `not_a_member` and `membership_inactive` denials into `slug_unknown`. This is anti-enumeration in action, documented in the test bodies.
- **New tests**:
  - `RuntimeRoleEnforcementTest` (3) — application datasource is `medcore_app` (not a superuser); RLS hides `tenancy.tenant` from raw queries with no GUC; live HTTP requests respect RLS-induced visibility.
  - `DatabaseRoleSafetyCheckTest` (3) — safety check passes for `medcore_app`, refuses startup for superuser with ADR-001 reference, wires as `ApplicationStartedEvent` listener.
  - `MedcoreAppPasswordSyncTest` (2) — VERIFY-only mode requires the password env var; VERIFY-only mode does NOT open a connection to the migrator datasource (proves the production posture through a throwing stub `DataSource`).

### Tightening pass before commit

The first 3E review pack was flagged: the running app process held migrator-level credentials in memory because `MedcoreAppPasswordSync` ran `ALTER ROLE` unconditionally on every startup. That weakened the security win.

**Re-scoped**: the sync became opt-in (default off). Production: VERIFY-only — ensure the configured password is non-blank, never call `ALTER ROLE`. Local/test: opt in via `MEDCORE_DB_APP_PASSWORD_SYNC_ENABLED=true`. The runbook explicitly warns "NEVER set in production". A `MedcoreAppPasswordSyncTest` proves the verify-only path doesn't even open a connection to the migrator datasource.

A residual was acknowledged honestly: even in VERIFY-only mode, Flyway runs in-process at startup, which means migrator credentials live in JVM memory until Flyway completes its first cycle. Eliminating that residual requires moving Flyway out-of-process for production — added to carry-forward as a separate ops slice.

### Status

- 19 files landed (6 new + 13 modified; +994 / −25).
- **74/74 green** across 16 suites.
- Committed and pushed under Tier 3 approval naming authentication/authorization, database migration, secrets handling.
- `main = origin/main = e690275`.
- Residual tracked forward to Phase 3F/3H: Flyway still in-process, so migrator credentials remain resident in the Hikari pool even under verify-only posture (proven unreachable by `MedcoreAppPasswordSyncTest`). Eliminating this requires out-of-process Flyway for production.

---

## Cross-cutting themes

### Iteration pattern that became routine

For every meaningful slice past 3A.3, the pattern was:

1. **Initial implementation** + review pack.
2. **External review (ChatGPT, treated as the user's voice per their explicit clarification)** — flags one or more concrete tightening items.
3. **Tightening pass** in the same uncommitted slice — fix the items, re-run tests, refresh the review pack.
4. **Tier 3 phrase regime** for commit + push (database migrations / authentication-security / audit logging are touched in nearly every slice).

The single exception was **3D**, where the user re-scoped the *language* (substrate vs. runtime hard gate) rather than the implementation. That re-scope itself was the tightening pass.

### Carry-forward discipline

Codified in ADR-004 §2.4. Every commit body lists deferred items; each subsequent commit body shows movement (closed / still open). At one point this list grew to 11 items; 3D and 3E together closed 2 (audit v2 hash chain; RLS substrate; runtime datasource role switch in flight).

### Test pack growth

| After commit | Test count |
| ---- | ---- |
| `93f44c5` (3A.2) | 1 (initial Spring context test) |
| `a06ee43` (3A.2 follow-up) | 3 (added Flyway state + schema assertions) |
| `32b635f` (3A.3) | 13 (3 + 4 + 6) |
| `6706824` (3B.1) | 31 (added 15 + 3) |
| `2e89c1e` (3C) | 50 (added 5 + 8 + 5 + 1) |
| `14dfc84` (ADR-004) | 50 (governance-only; no code) |
| `24e5e43` (3D) | 66 (added 5 + 4 + 6 + 1) |
| Working tree (3E in flight) | 74 (added 3 + 3 + 2) |

### Test infrastructure decisions worth remembering

- **Testcontainers + in-process `mock-oauth2-server`** — backend tests are fully self-contained. `make api-test` does not depend on `docker compose`.
- **Real DB-level grant tests** — `AuditImmutabilityTest` connects directly as `medcore_app` and asserts UPDATE/DELETE/TRUNCATE fail with `permission denied`. Same pattern reused by `TenancyRlsTest`.
- **`adminDataSource` qualified bean (3E)** — exposes the container superuser for fixtures the runtime role can't perform. Unqualified autowires resolve to `medcore_app` so misuse is loud.
- **`@TestConfiguration` + `@Primary` PoisonableAuditWriter** — the cleanest way to prove ADR-003 §2 atomicity end-to-end without mock frameworks.

### Architectural boundaries that have held

- **Modular monolith with one-way `feature → platform` deps.** `tenancy` and `identity` import from `platform/*`; `platform/*` never imports from feature modules. Enforced by code review (caught and fixed once during 3B.1's polish pass — enums leaking from `tenancy/domain` into `platform/tenancy`).
- **No cross-module foreign keys.** `tenancy.tenant_membership.user_id` is an opaque `UUID`, no `REFERENCES identity."user"`.
- **Controllers return DTOs only.** No JPA entities or principals leak across the wire.
- **Audit writes are synchronous and atomic with the audited action.** ADR-003 §2 / Rule 06.
- **No "auth disabled in dev" switch.** ADR-002 §3.

### Governance moments worth remembering

- **`9b8dea4`** — three ADRs landed together. Set the direction for ten subsequent commits.
- **`14dfc84`** — proposed standing-autonomy workflow conflicted with §§4.7/4.8. Per §4.1, the conflict was flagged and routed through a properly-governed ADR rather than silently adopted. The ADR considered and *rejected* a "compound `approved to commit and push` phrase" alternative on the same precedent.
- **3C re-prompt** — first 3C prompt called for a no-op fire-and-forget audit. Conflict with ADR-003 + Rule 06 was flagged; corrected master prompt was issued.
- **3D re-scope** — first 3D review pack overstated runtime enforcement. Re-scoped before commit; `runtime datasource switch` made an explicit top-priority carry-forward.
- **3E tightening** — the running app shouldn't rotate role passwords in production. Sync became opt-in; default verify-only; residual (Flyway in-process) acknowledged honestly with a tracked carry-forward.

---

## VS1 — First clinician vertical slice (2026-04-23..24)

### What VS1 is

The first slice across all layers — auth → tenancy → PHI RLS →
write gate → audit → read gate → UI — that executes a complete
clinician workflow: **login → patient → start encounter → write
note → save**. VS1 is not a phase; it's a milestone that draws a
thin line through Phases 3K, 4A, 4B, 4C, 4D, and 4D.3/4D.4 to
prove end-to-end the plumbing works together under real browser
conditions.

The full review pack — commits, evidence files, gates, and
carry-forwards — lives at `docs/evidence/vs1-review-pack.md`.
This section is the chronological narrative.

### Chunk A — Frontend scaffolding + demo login (`6857759`)

React 19 + Vite 6 + TypeScript strict + Tailwind v4 + shadcn/ui
+ TanStack Query v5. Paste-token `/login` page, in-memory token
store (no `localStorage` / `sessionStorage` — PHI discipline),
minimal tenant list at `/`. First end-to-end auth surface in the
browser. Landed under looser discipline than later chunks (see
§3.7 pointer in AGENTS.md); the governance incident note at
`docs/evidence/incident-2026-04-23-chunk-a-b-tier-trailer-skip.md`
records the disposition.

### Chunk B — Patient list (`d1e381f` frontend; `5ce4e7d` backend)

First PHI-render surface in the browser. Backend added the list
endpoint with paging + `CLINICAL_PATIENT_LIST_ACCESSED` audit
including empty-list calls; frontend wired the table with
MRN-link navigation. Same looser-discipline window as Chunk A.

### Governance remediation (`da414b3` + `12ee0c5` + `6d12995`)

Mid-slice discovery: Chunks A and B shipped without `Tier:`,
`Roadmap-Phase:`, or carry-forward trailers. Remediation
landed as **Option B-light** — forward controls installed; prior
commits NOT rewritten. Ships AGENTS.md §3.7 pointer to Rule 07,
the Rule 07 naming-conventions rule itself, and the incident
note. Runbook pinned stable subject + post-1900 birth dates.

### Chunk C — Patient detail (`28a6817`)

Patient demographics + identifier list on `data-phi`-tagged
container. First use of `data-phi` as the frontend PHI boundary
convention that Chunks D / E / F all extend.

### Chunk D — Encounter start (`75e6ac2` + `b1ff0c3`)

Backend (4C.1): V18 `clinical.encounter`, `StartEncounter` +
`GetEncounter` stacks, new `ENCOUNTER_*` authorities, two new
audit actions. Frontend (4C.2): Start-encounter button on patient
detail + minimal encounter detail page. First clinical write that
is not a patient-demographic write.

### Chunk E — Encounter notes (`c45e16a` + `13007d9`)

Backend (4D.1): V19 `clinical.encounter_note`, append-only
create + list stacks, new `NOTE_*` authorities, two new audit
actions. Reason slugs carry `count:N` on list and NO body /
length / hash on create — strict PHI discipline. Frontend (4D.2):
note editor + list on encounter detail page with `data-phi` at
the card root, clear-on-save, newest-first list, 403 banner.

### Chunk F — Playwright + DoD + PHI-leakage scan (`1dc34ef`)

First automated proof the VS1 loop works end-to-end and that the
PHI-discipline claims hold. Ships `@playwright/test` + Chromium,
an admin-DS seeder, and two specs (happy-path + PHI-leakage).
Measured on 5 consecutive runs: 3 clicks median, 158 ms median
to note-saved, 0 PHI sentinels in storage / cookies / console.
Evidence: `docs/evidence/vs1-dod-measurement.md`.

### Chunk G — CI + VS1 close-out governance (this commit, 4D.4)

This slice. Two new CI jobs (`web` + `e2e`) so the DoD + PHI
discipline Chunk F proved locally is enforced at merge time.
Runbook updates, roadmap markers for the landed VS1 portions
of Phases 4C and 4D, this timeline entry, and a VS1 review pack.
Branch-protection toggle is a manual operator action (called out
in `docs/runbooks/ci-cd.md` §3).

### Cross-cutting themes from VS1

- **`data-phi` as the frontend PHI boundary** (Chunks C, D, E).
  One tag at the card root rather than per-field. Makes the
  PHI-leakage spec scan a single surface per card.
- **Role expansion pattern** (Chunks D, E). Every new clinical
  surface extends `MedcoreAuthority` + `MembershipRoleAuthorities`
  + `MembershipRoleAuthoritiesTest` in the same commit. Zero
  room to forget to grant a role.
- **Closed-enum audit reason slugs with no PHI** (Chunks D, E).
  Reasons like `intent:clinical.encounter.note.list|count:N`
  are testable + forensic without carrying PHI. Every new
  clinical write adds its slug family to `AuditAction`.
- **PHI-not-in-reason as a test assertion** (Chunks D, E). Every
  clinical write ships a test that greps stdout + audit rows for
  distinctive body text and asserts zero hits.
- **Audit rows are never deleted** (Chunks E test cleanup, F
  seeder). Test cleanup deletes encounters + notes + patients
  but preserves `audit.audit_event` per ADR-003.
- **Vertical-slice sequencing over horizontal polish** (all
  chunks). UI is deliberately bare shadcn defaults; design-system
  work is explicitly deferred to a later phase so plumbing gets
  proven first. The Chunk G evidence doc documents the ≈ 570×
  safety margin on the 90 s DoD — that margin is a sequencing
  artifact, not a sign the DoD is wrong.

---

## Open carry-forward (as of VS1 Chunk G)

Items still deferred — every commit body since 3C has carried
its slice-specific list forward. Below is the consolidated state
as VS1 closes.

### Platform (from 3A..3E)
- **Move Flyway out-of-process for production** (3E) — the JVM
  still briefly holds migrator credentials in memory. Tracked.
- **Password rotation as a first-class flow** (3E).
- **`medcore_migrator` as a distinct provisioned production role** (3E).
- **Per-tenant chain sharding** (3D) — single global chain at v2.
- **Chain verification endpoint / scheduled job** (3D) — function
  exists and is grant-available; no operator surface yet.
- **RLS policies for tenancy write/admin surfaces** (3D) —
  covered when the admin slice lands.
- **Central request-ID generator** (3C).
- **Proxy-aware `client_ip` extraction** (3C).
- **Uniform 401 envelope** (3B.1).
- **`TenantContextMissingException` HTTP mapping** (3B.1).
- **Concurrent first-login retry** (3A.3).

### Clinical (from 4A..4D)
- **Per-patient encounter list UI + resume-open-encounter UX** —
  Phase 4C full exit.
- **Encounter state transitions** (PLANNED / FINISHED /
  CANCELLED / entered-in-error) — Phase 4C full exit.
- **Provider attribution on encounters** — Phase 4C full exit.
- **FHIR `Encounter` read endpoint** — Phase 4C full exit.
- **Note signing workflow** (immutable-once-signed) — Phase 4D full exit.
- **Note amendments** (`amends_id` FK, new row per amendment) — Phase 4D.
- **Note templates** (SOAP / H&P / progress / procedure) — Phase 4D.
- **Structured note sections** (subjective / objective / …) — Phase 4D.
- **FHIR `DocumentReference` surface** — Phase 5A.
- **Sign-note ≤ 1-click DoD** — Phase 4D full exit.

### Frontend + UX (from VS1 A..G)
- **Design-system phase** — tokens, typography scale, spacing
  scale, icon set, density modes, dark mode, Storybook. VS1 runs
  on bare shadcn defaults deliberately.
- **A11y (axe) audit in E2E** — design-system phase.
- **Visual regression** (Percy / Chromatic / Playwright snapshots) —
  design-system phase.
- **`packages/ui` extraction** — post-VS1.
- **`packages/api-client` extraction** — post-VS1 (also 3A.3 residual).
- **`packages/schemas`-driven types** — post-VS1.
- **i18n + locale-aware date formatting** — post-VS1.
- **Mobile / tablet viewports** — Phase 6C.
- **Keyboard-first shortcut surface** (Ctrl/Cmd-Enter save etc.) — UX slice.

### CI + enforcement (from VS1 Chunk G)
- **Firefox + WebKit matrix** on `e2e` — post-VS1.
- **Lighthouse CI / Playwright performance budgets** — post-VS1.
- **Mutation testing** (Stryker etc.) — deferred.
- **E2E sharding / parallel workers** — when runtime crosses 2 min.
- **Flake dashboard / historical trend** — later observability slice.
- **CODEOWNERS + required-reviewer matrix** — separate governance slice.
- **Dependabot / Renovate policy** — separate governance slice.
- **Deploy pipelines** — Phase 3H follow-on (3I.3..3I.6).
- **DOM hidden-field + network-response-cache + service-worker
  PHI scans** — dedicated PHI-hardening slice.
- **Bundled login-to-landing workflow DoD** — separate
  onboarding-DoD slice.
- **`docs/project-timeline.md` per-phase narrative backfill for
  Phases 3F..4B.1** — tracked as Tier 1 doc-only slice.

---

## Reference

- Repository: `https://github.com/SinghSation/Medcore`
- Branch governed: `main` only
- Local working directory referenced throughout: `/Volumes/BackUP/Medcore`
- Active governance: `AGENTS.md` (Phase 0, amended at `14dfc84`), ADR-001 / ADR-002 / ADR-003 / ADR-004
- Active rules: `.cursor/rules/00-06`
- Active skills: `.claude/skills/api-contract-first` / `new-module` / `phi-exposure-review` / `safe-db-migration` / `safe-local-commit`

---

## Document update log

This doc is append-only narrative. Factual state (commit SHA, status, counts) is corrected in place when a slice lands; narrative content is never rewritten. Each material update is recorded below.

- **2026-04-21** — Phase 3E marked landed at `e690275`. Factual-state corrections applied to the at-a-glance table, totals, the Phase 3E heading, and the Status subsection. Carry-forward header retitled from "as of in-flight 3E" to "as of `e690275`". No narrative content modified.
- **2026-04-24** — VS1 Chunk G landing. "At a glance" table extended with rolled-up rows for Phases 3F..4A.5 and one row per VS1 chunk. New `## VS1 — First clinician vertical slice` section added between the Phase 3E narrative and the Open carry-forward block. Carry-forward header retitled from "as of `e690275`" to "as of VS1 Chunk G" and reorganized into Platform / Clinical / Frontend+UX / CI categories. A tracked carry-forward captures the narrative gap for Phases 3F..4B.1 — the at-a-glance rows are accurate but per-phase narrative sections were skipped during landing and will backfill in a Tier 1 doc-only slice.
