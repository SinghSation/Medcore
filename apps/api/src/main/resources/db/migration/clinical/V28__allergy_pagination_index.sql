-- V28__allergy_pagination_index.sql — platform-pagination chunk D
--
-- Cursor-paginated reads on `clinical.allergy` sort by
-- `(status_priority, createdAt DESC, id DESC)` per ADR-009 §2.5
-- where status_priority is:
--   ACTIVE = 0, INACTIVE = 1, ENTERED_IN_ERROR = 3
-- (RESOLVED = 2 is reserved for problems; allergies skip it).
--
-- ### Why a stored generated column, not a raw-status index
--
-- The repository's ORDER BY uses `(CASE status WHEN 'ACTIVE'
-- THEN 0 ... END)`, but `status` is stored as a `TEXT`
-- (Hibernate `@Enumerated(EnumType.STRING)`) so PG sorts those
-- enum strings alphabetically — not in the priority order the
-- application semantics demand. A raw-status composite index
-- (the original V28 shape, pre-revision) cannot satisfy the
-- ORDER BY: PG falls back to a Sort node after filtering,
-- defeating the "index-resolved cursor walk" claim.
--
-- The fix: a SMALLINT generated-stored column whose value is
-- exactly the priority integer the cursor encodes. The index
-- on `(tenant_id, patient_id, status_priority, created_at,
-- id)` then matches the ORDER BY one-for-one.
--
-- `GENERATED ALWAYS AS (...) STORED` guarantees the column
-- always tracks `status` — there is no application code path
-- that can drift the two apart. Updates to `status` flow
-- through to `status_priority` automatically.
--
-- ### Online DDL posture (NORMATIVE — see ADR-006 + V26 KDoc)
--
-- This Flyway migration uses plain `CREATE INDEX` and a normal
-- transactional `ALTER TABLE`. Both are instant on the dev/CI
-- empty `clinical.allergy` and on the pre-prod baseline.
-- Production deployments coordinate the actual online build
-- via the runbook (`CREATE INDEX CONCURRENTLY` + `pg_repack`-
-- style column add) executed by the operator session, then
-- mark the migration applied in `flyway_schema_history`.
-- Rationale + reproduction of the Flyway↔CONCURRENTLY
-- deadlock that motivated this split is documented in V26.
--
-- ### Forward-only discipline
--
-- V28 has not yet been merged to `main` — this migration is
-- being revised in place during PR review (CodeRabbit Major #3
-- / ORDER BY index-mismatch finding). Once merged, the
-- standard "no rewriting Flyway history" rule applies and
-- subsequent corrections must land in V30+.
--
-- ### Rollback
--
-- `DROP INDEX CONCURRENTLY IF EXISTS clinical.ix_allergy_pagination;`
-- + `ALTER TABLE clinical.allergy DROP COLUMN status_priority;`
-- — pagination falls back to a Sort scan, correct but slower.
-- Reversible.

ALTER TABLE clinical.allergy
    ADD COLUMN IF NOT EXISTS status_priority SMALLINT
    GENERATED ALWAYS AS (
        CASE status
            WHEN 'ACTIVE'           THEN 0
            WHEN 'INACTIVE'         THEN 1
            WHEN 'ENTERED_IN_ERROR' THEN 3
        END
    ) STORED;

CREATE INDEX IF NOT EXISTS ix_allergy_pagination
    ON clinical.allergy (tenant_id, patient_id, status_priority, created_at, id);
