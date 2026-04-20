---
name: safe-db-migration
description: Use for any database schema change in Medcore. Enforces the expand/backfill/contract pattern, reversibility, tenancy, and audit requirements.
---

# Skill: safe-db-migration

Schema changes are the most dangerous class of change in a clinical system.
Follow this skill for every migration — even the ones that "look safe."

## When to use

- Any `CREATE`, `ALTER`, `DROP`, or `RENAME` at the database layer.
- Any change to indexes, constraints, views, materialized views, or stored
  routines.
- Any backfill that touches more than a trivial number of rows.
- Any change to row-level security policies.

## Non-negotiable prerequisites

Before writing the migration:

- [ ] The table's module owner is identified.
- [ ] The change is specified in the module's schema (`packages/schemas/`)
      if the change affects a contract.
- [ ] The data classification of every affected column is known.
- [ ] If the change is **dangerous** (see below), an ADR is open.

A **dangerous** change is any of:

- Dropping a table, column, or index.
- Renaming a table or column in place.
- Adding a `NOT NULL` constraint to a populated column without a safe default.
- Changing a column type in a non-widening way.
- Adding a unique constraint to a populated column.
- A backfill of more than a trivial row count.
- An operation that may hold a long lock on a hot table.

## Procedure

### 1. Design the change

Decide whether the change can ship atomically or requires the
**expand / backfill / contract** pattern.

- **Atomic** is permitted when the operation is fast, non-blocking, and
  reversible (e.g., adding a nullable column on a small table, adding an
  index with the concurrent builder, adding a `CHECK` that is already
  satisfied).
- **Expand / backfill / contract** is REQUIRED for dangerous changes:
  1. **Expand** — deploy the migration that adds the new structure. Old
     code still works.
  2. **Backfill** — run a rehearsed, idempotent backfill in batches with
     throttling and progress tracking. Measure lock contention on a staging
     snapshot first.
  3. **Contract** — once new code has burned in and the backfill is
     complete, remove the old structure in a separate release.

Each phase is a distinct migration and, typically, a distinct deploy.

### 2. Author the migration

Every migration file MUST contain:

- **Up** — the forward change.
- **Down** — the explicit reversal. If a full reversal is infeasible
  (e.g., a true data drop), the migration file MUST link to the ADR and
  document the manual rollback procedure. Silent non-reversibility is
  prohibited.
- **Locking note** — a comment stating the lock mode and expected duration
  on the target engine.
- **Idempotency** — the migration can be re-applied safely if interrupted.

Use the engine's online primitives where available:

- PostgreSQL: `CREATE INDEX CONCURRENTLY`, `ALTER TABLE … VALIDATE
  CONSTRAINT`, `NOT VALID` initially, then validate in a second step.
- Keep statements single-purpose. One `ALTER TABLE` per statement.

### 3. Preserve invariants

- Every PHI table MUST retain: `id`, `tenant_id`, `created_at/by`,
  `updated_at/by`, `deleted_at`, and the row-versioning column.
- Row-level security / tenancy enforcement MUST remain enabled across the
  migration.
- Foreign keys MUST stay enforced at the DB layer. If a migration drops and
  recreates an FK, the gap MUST be documented and time-bounded.

### 4. Backfill plan

For backfills:

- Batch size is bounded and declared.
- The backfill is idempotent and resumable.
- The backfill is instrumented: progress metric, duration, error rate.
- The backfill is rehearsed on a production-sized staging snapshot.
- The backfill is runnable from an operator entrypoint, not from the
  application's hot path.

### 5. Rollback plan

Every migration PR MUST include:

- The exact steps to reverse the change at each phase.
- How to detect that rollback is needed (symptom → signal).
- Expected time-to-rollback.
- Data implications of rolling back (what is lost, what remains).

If rollback implies data loss, the ADR MUST call this out explicitly.

### 6. Tests

- The migration runs forward and backward on an ephemeral database
  seeded with representative fixtures.
- The contract tests for any touched module still pass against the new
  schema.
- An adversarial test exercises tenant isolation on the changed table.

### 7. Deployment

- Dangerous migrations deploy behind a feature flag when the application
  code can select between old and new paths.
- Backfills run out-of-band, not during the web deploy.
- The contract phase does not ship in the same deploy as the expand phase.

### 8. PR checklist

- [ ] Migration has explicit up and down (or documented manual rollback).
- [ ] Locking behavior is commented in the migration file.
- [ ] Tenancy and audit requirements preserved.
- [ ] ADR attached for dangerous changes.
- [ ] Backfill plan rehearsed on staging snapshot.
- [ ] Rollback plan documented.
- [ ] Migration tested forward and backward in CI.
- [ ] Retention document updated if retention changes.

Assistants MUST NOT merge or mark a migration complete without every box
checked.
