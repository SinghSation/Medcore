-- V15__patient_mrn_counter.sql — Phase 4A.2
--
-- Adds the per-tenant MRN counter that `MrnGenerator` uses to mint
-- Medcore-scoped Medical Record Numbers on patient creation.
--
-- ### Why a separate table (and not a Postgres SEQUENCE)
--
-- Per-tenant generation constraint rules out a single global
-- sequence. Per-tenant SEQUENCE objects would scale linearly with
-- tenants (hundreds to thousands of sequence objects), are awkward
-- for `pg_dump` / restore, and cannot carry tenant-configurable
-- prefix / width metadata without wrapping them anyway. A per-
-- tenant row in a dedicated table is the clean alternative —
-- one row per tenant, pessimistic contention limited to that
-- tenant's counter, and the row carries formatting metadata
-- (`prefix`, `width`, and forward-looking `format_kind`) so the
-- generator can evolve without schema churn.
--
-- ### Concurrency contract (MUST read before editing)
--
-- `MrnGenerator` uses a single atomic
--   INSERT ... VALUES (...) ON CONFLICT (tenant_id) DO UPDATE
--   SET next_value = patient_mrn_counter.next_value + 1 ...
--   RETURNING next_value, prefix, width, format_kind;
-- This is the ONLY supported path to advance the counter. Two
-- concurrent transactions targeting the same tenant serialize via
-- Postgres's uniqueness machinery — the second INSERT loses,
-- flips to DO UPDATE, and reads the value the first committed.
-- Monotonicity + uniqueness are Postgres's to guarantee; we do
-- not add an explicit `FOR UPDATE` lock.
--
-- If the containing transaction aborts for ANY reason (patient
-- INSERT violates a CHECK, duplicate-warning throws, audit
-- emission fails), the counter bump rolls back with it. MRNs
-- are never silently "consumed" — rollback test in 4A.2 chunk F
-- proves this normatively (`MrnRollbackTest`).
--
-- ### Format extensibility (per 4A.2 design-pack refinement #1)
--
-- Columns are shaped so the generator does NOT assume numeric-
-- only format:
--   - `prefix`      — arbitrary prefix string (default empty).
--   - `width`       — zero-pad width for the numeric part (0 = no pad).
--   - `format_kind` — TEXT + CHECK; current values: `NUMERIC`.
--                     Future: `ALPHANUMERIC`, `CHECK_DIGIT_MOD10`, etc.
--                     Adding a new format kind is additive (enum
--                     update + CHECK constraint migration + generator
--                     branch), not a breaking schema change.
--
-- ### RLS
--
-- Both-GUCs policies parallel to V14's clinical.patient table.
-- SELECT / UPDATE require ACTIVE membership in the tenant.
-- INSERT is additionally gated on OWNER/ADMIN role — counter rows
-- are bootstrapped implicitly on first patient-create (the upsert
-- path), and that operation runs under a caller who already holds
-- PATIENT_CREATE (OWNER/ADMIN only).
--
-- ### Rollback
--
-- DROP TABLE clinical.patient_mrn_counter. Acceptable at 4A.2
-- scale because no durable counter state yet has a compliance
-- meaning — each tenant's counter starts at 1. Once 4A.2 ships
-- to any environment that has minted real MRNs, the counter
-- value IS durable state: rolling back the table silently
-- resets the counter, risking MRN collision with historical
-- records. Forward-only from then on.

-- -----------------------------------------------------------------------
-- Table
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS clinical.patient_mrn_counter (
    tenant_id    UUID         NOT NULL,
    prefix       TEXT         NOT NULL DEFAULT '',
    width        INT          NOT NULL DEFAULT 6,
    format_kind  TEXT         NOT NULL DEFAULT 'NUMERIC',
    next_value   BIGINT       NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    row_version  BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_clinical_patient_mrn_counter
        PRIMARY KEY (tenant_id),
    CONSTRAINT fk_clinical_patient_mrn_counter_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant (id),
    CONSTRAINT ck_clinical_patient_mrn_counter_format_kind
        CHECK (format_kind IN ('NUMERIC')),
    CONSTRAINT ck_clinical_patient_mrn_counter_width_nonneg
        CHECK (width >= 0 AND width <= 32),
    CONSTRAINT ck_clinical_patient_mrn_counter_next_value_positive
        CHECK (next_value >= 1)
);

-- -----------------------------------------------------------------------
-- Row-level security
-- -----------------------------------------------------------------------
ALTER TABLE clinical.patient_mrn_counter ENABLE  ROW LEVEL SECURITY;
ALTER TABLE clinical.patient_mrn_counter FORCE   ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE ON clinical.patient_mrn_counter TO medcore_app;

-- DELETE is deliberately NOT granted. Counter rows should never be
-- removed in normal operation; any cleanup path goes through the
-- migrator role.

-- ---- SELECT ----
-- Both-GUCs + ACTIVE membership check. The counter row is scoped
-- to the tenant and readable by any ACTIVE member. The stronger
-- role gate lives on INSERT/UPDATE below.
DROP POLICY IF EXISTS p_patient_mrn_counter_select ON clinical.patient_mrn_counter;
CREATE POLICY p_patient_mrn_counter_select
    ON clinical.patient_mrn_counter
    FOR SELECT
    TO medcore_app
    USING (
        clinical.patient_mrn_counter.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.patient_mrn_counter.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
        )
    );

-- ---- INSERT (OWNER/ADMIN only) ----
-- Bootstrap on first patient-create. The caller already holds
-- PATIENT_CREATE (OWNER/ADMIN) to reach this path; the role gate
-- here is defence in depth if an app-layer bug ever invokes the
-- generator without the WriteGate.
DROP POLICY IF EXISTS p_patient_mrn_counter_insert ON clinical.patient_mrn_counter;
CREATE POLICY p_patient_mrn_counter_insert
    ON clinical.patient_mrn_counter
    FOR INSERT
    TO medcore_app
    WITH CHECK (
        clinical.patient_mrn_counter.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.patient_mrn_counter.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

-- ---- UPDATE (OWNER/ADMIN only) ----
-- Every MRN mint after the first bootstrap runs through this path
-- via ON CONFLICT DO UPDATE. Same role gate as INSERT.
DROP POLICY IF EXISTS p_patient_mrn_counter_update ON clinical.patient_mrn_counter;
CREATE POLICY p_patient_mrn_counter_update
    ON clinical.patient_mrn_counter
    FOR UPDATE
    TO medcore_app
    USING (
        clinical.patient_mrn_counter.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.patient_mrn_counter.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    )
    WITH CHECK (
        clinical.patient_mrn_counter.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.patient_mrn_counter.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );
