# ADR-001: Adopt PostgreSQL + Flyway with row-level multi-tenancy by `tenant_id`; RLS deferred to Phase 3D

- **Status:** Proposed
- **Date:** 2026-04-20
- **Authors:** Gurinder Singh
- **Reviewers:** Gurinder Singh (repository owner)
- **Supersedes:** none
- **Related:** ADR-002 (identity), ADR-003 (audit), `docs/architecture/medcore_architecture_charter.md` §§3–4,
  `.cursor/rules/03-db-migrations.mdc`, `AGENTS.md` §3.3

---

## 1. Context

Phase 3 introduces the first persistence-backed modules in Medcore (identity,
tenancy, audit). The charter and governance rules constrain the choice:

- `AGENTS.md` §3.3 requires every PHI-bearing table to have row-level tenancy,
  audit columns, and a documented retention policy.
- `.cursor/rules/03-db-migrations.mdc` requires forward-only, reversible
  migrations with documented rollback plans; prohibits ad-hoc SQL; requires
  row-level security where the database supports it.
- The charter (§4) commits Medcore to a **modular monolith with strict
  internal boundaries**: each module owns its own persistence and exchanges
  data with other modules only through typed contracts — no cross-module
  database joins.
- Medcore is built by a solo operator under cost-conscious constraints
  (§1.5). Infrastructure choices must minimise operational burden today while
  preserving a credible production path.

No relational store has been selected yet. No migration tooling has been
selected. No tenancy model has been decided. Downstream modules cannot begin
without these commitments.

A separate concern is **when** to enable PostgreSQL row-level security
(RLS). Designing schemas with `tenant_id` from day one is non-negotiable.
**Service-layer isolation is the temporary enforcement mechanism for
Phases 3A–3C. Database-enforced RLS is mandatory before any Phase 4 or
PHI-bearing work begins.** Enabling RLS policies in Phase 3A, before the
request/auth/tenant-context path is stable, adds opaque failure modes
disproportionate to the Phase 3A goal. Separating "design the schema for
RLS now" from "turn RLS on as a hard gate before PHI" sequences the work
correctly without weakening the target posture.

## 2. Decision

**We will adopt PostgreSQL 16 as the primary relational store, Flyway as the
migration tool, and a row-level multi-tenancy model keyed on a `tenant_id`
column in every tenant-scoped table. RLS will be designed into the schema
from the first migration but enabled and enforced via policies only in
Phase 3D hardening, and Phase 3D enablement is a hard gate before any
Phase 4 or PHI-bearing work.**

Specifics:

- **Store:** PostgreSQL 16. Local dev via `docker-compose` (`postgres:16`).
  Production hosting (RDS / Supabase / Neon / Aiven / …) is deferred to a
  separate ADR driven by real compliance, residency, and cost inputs.
- **Migrations:** Flyway, forward-only. One directory per module under
  `apps/api/src/main/resources/db/migration/<module>/`. Dangerous changes
  follow `.claude/skills/safe-db-migration.md`.
- **Schemas:** one PostgreSQL schema per module: `identity`, `tenancy`,
  `audit`, plus further schemas as modules land.
- **Tenancy model:** row-level. Every tenant-scoped table carries
  `tenant_id UUID NOT NULL`. Non-tenant tables (e.g., `identity.user`) do
  not carry it.
- **Cross-module references:** IDs only (opaque UUIDs), **never** cross-module
  foreign keys. A module enforces referential correctness inside its own
  schema and via its public service API, not via cross-schema FKs.
- **Audit columns:** every non-audit table carries `created_at`, `updated_at`,
  `created_by`, `updated_by`, `row_version`, and `deleted_at` where soft
  delete is semantically useful. The `audit.audit_event` table is append-only
  and carries no `deleted_at` / `row_version` (see ADR-003).
- **RLS:** policies are written into a Phase 3D migration (`audit/V…__rls.sql`
  and `tenancy/V…__rls.sql`) that enables RLS and installs per-row predicates
  keyed on session GUCs `app.current_user_id` and `app.current_tenant_id`,
  set per-request by a JPA session interceptor. Service-layer membership
  checks remain the enforcement mechanism during Phases 3A–3C; RLS
  enablement is a hard precondition for Phase 4 and for any PHI-bearing
  table ever being created.

## 3. Alternatives Considered

### MySQL / MariaDB
Mature, widely hosted, BAA-available options exist. Rejected because
PostgreSQL's `jsonb`, partial indexes, generated columns, richer `CHECK`
constraints, `citext`, and first-class row-level security make it a
materially stronger substrate for a clinical platform. Reconsidering would
require a compelling new forcing function.

### Schema-per-tenant or database-per-tenant
Stronger isolation at the cost of operational complexity that scales with
tenant count. Rejected for Phase 3 because a solo operator cannot sanely
run N Postgres schemas/DBs' worth of migrations, backups, and monitoring at
this scale. Row-level tenancy covers the same threat surface with proper
RLS and is the industry default for SaaS EHR-adjacent products. We could
reconsider for a single very large anchor tenant later; that decision would
be a new ADR.

### NoSQL (DynamoDB, Firestore, MongoDB)
Rejected. Clinical data is inherently relational, and HIPAA/audit patterns
are far easier on an RDBMS. Using NoSQL would trade a small greenfield win
for persistent model-mapping friction.

### Liquibase instead of Flyway
Either tool would work. Flyway is chosen because its SQL-first model aligns
with how we want migrations reviewed (plain SQL is the authoritative form;
Kotlin migrations are available where needed). Liquibase's XML/YAML
changelog layer adds a translation step we don't need today.

### Enable RLS in Phase 3A
Rejected for sequencing, not for security posture. Enabling RLS before the
request/auth/tenant-context path is stable creates opaque failures that are
expensive to diagnose for a solo operator. Designing for it from day one
retains the invariant; enabling it in Phase 3D — as a hard gate before any
PHI work — delivers the same end state with less risk of mid-phase churn.

## 4. Consequences

### 4.1 Positive
- A single, widely supported engine covers identity, tenancy, audit, and
  future PHI tables with no change of substrate.
- Flyway migrations become the single record of schema history and are
  trivially reviewable as SQL.
- Row-level tenancy preserves the option to run thousands of tenants on one
  database without schema multiplication.
- Staging RLS enablement behind a hard Phase 3D gate keeps the invariant
  honest while letting Phase 3A–3C ship quickly.

### 4.2 Negative
- Between Phase 3A and Phase 3D, tenant isolation depends on correct
  application code. A service bug could leak data that RLS would have
  caught. The gate at the end of Phase 3D is the compensating control.
- Every tenant-scoped table carries `tenant_id` forever; non-trivial rename
  or split of tenancy semantics later is expensive.
- Postgres operational depth (vacuum tuning, replication, long-running
  transaction hygiene) is now part of the operator's job.

### 4.3 Risks & Mitigations
- **Phase 3D RLS gate is skipped or deferred.** The gate is the entire
  reason this ADR ships `tenant_id` with non-enforced policies. It MUST
  land before any Phase 4 work begins and before any PHI-bearing table
  is created. A tracking issue referencing this ADR is opened at the
  moment Phase 3D opens.
- **Wrong `tenant_id` nullability.** Policy: `tenant_id NOT NULL` on every
  tenant-scoped table. The only exceptions allowed are cross-tenant admin
  events in `audit.audit_event`, documented in ADR-003.
- **Forgetting `tenant_id` on a new PHI table later.** Mitigation: a
  CI-time migration linter (follow-up) rejects PHI-tagged tables without
  `tenant_id` and without RLS enabled.
- **Enabling RLS later breaks existing queries.** Mitigation: Phase 3D
  includes an RLS-on integration test matrix run against Testcontainers;
  the migration that enables RLS is the same commit that adds the policies.

## 5. Compliance & Security Impact

- **HIPAA** (45 CFR §164.312(a)(1), technical access controls): row-level
  tenancy prepares for least-privilege access to PHI by tenant boundary.
  RLS enforcement arrives in Phase 3D before any PHI table.
- **SOC 2 CC6.1:** logical access enforcement is application-layer during
  3A–3C, database-layer from 3D. This ADR documents the timeline and
  states the Phase 3D gate explicitly.
- **Retention:** retention policy per table lives in
  `docs/compliance/retention.md` (to be authored). This ADR does not set
  retention horizons.
- **Data residency:** single-region managed Postgres assumed. Residency
  rules and multi-region decisions are out of scope and belong to the
  production-hosting ADR.

## 6. Operational Impact

- **Local dev:** one additional container in `docker-compose.yml`.
- **CI:** Testcontainers spins an ephemeral Postgres per test class.
- **Production hosting:** deferred. Managed Postgres with BAA is the
  expected baseline; the specific provider will be chosen under a separate
  ADR when the first PHI-handling commit is near.
- **Backups, DR, PITR:** covered by the production-hosting ADR.
- **Flyway baselining:** an initial empty `V000__baseline.sql` per module
  schema establishes Flyway's history without inhibiting downstream
  migrations.

## 7. Rollout Plan

1. Add Postgres to `docker-compose.yml` and a runbook section in
   `docs/runbooks/local-services.md`.
2. Add Flyway + Postgres driver + Testcontainers to `apps/api/build.gradle.kts`
   with an empty baseline migration per module schema.
3. Configure `spring.jpa.hibernate.ddl-auto=validate`. Any PR that sets this
   to `update` or `create` is rejected.
4. First real migration: `identity/V001__user.sql` (ADR-002 territory).
5. First tenancy migrations: `tenancy/V001__tenant.sql`,
   `tenancy/V002__tenant_membership.sql`.
6. First audit migration: `audit/V001__audit_event.sql` (ADR-003 territory).
7. **Phase 3D hardening:** migrations that enable RLS and add the
   per-request GUC setter. Gated by its own ADR (candidate ADR-004).
   **No PHI-bearing table may be created before this migration lands.**
8. Verification at every step: Testcontainers integration tests pass;
   `spring.jpa.hibernate.ddl-auto=validate` passes against a freshly
   migrated database.

**Rollback plan:**
- Per-migration rollback follows the module-specific plan documented in the
  migration header.
- Switching away from PostgreSQL is treated as a superseding ADR with a
  cost-justified rewrite plan. In Phase 3 no data yet exists that would
  be lost.
- Disabling RLS after enabling it is a single `ALTER TABLE … DISABLE ROW
  LEVEL SECURITY` per table, coordinated via migration. Reversible, but
  not permitted once any PHI table exists.

## 8. Acceptance Criteria

- [ ] `docker-compose.yml` runs `postgres:16` locally.
- [ ] Flyway + Postgres + Testcontainers added to `apps/api/build.gradle.kts`.
- [ ] Empty baseline migration exists for each of `identity`, `tenancy`,
      `audit`.
- [ ] JPA config sets `ddl-auto=validate`.
- [ ] Every tenant-scoped table produced in Phase 3A–3C includes
      `tenant_id NOT NULL`.
- [ ] No cross-module foreign keys exist between schemas.
- [ ] Phase 3D has a tracked issue referencing this ADR for RLS enablement,
      flagged as a hard precondition for any Phase 4 or PHI-bearing work.

## 9. References

- `docs/architecture/medcore_architecture_charter.md` §§3–4
- `AGENTS.md` §3.3
- `.cursor/rules/03-db-migrations.mdc`
- `.claude/skills/safe-db-migration.md`
- PostgreSQL 16 documentation — Row Security Policies
- Flyway — Forward-only migration pattern
