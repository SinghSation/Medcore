-- V18__clinical_encounter.sql — Phase 4C.1 (VS1 Chunk D)
--
-- First clinical encounter surface in Medcore. Establishes the
-- `clinical.encounter` table with a narrow initial scope — the
-- VS1 "start encounter" workflow needs one row per started visit
-- with a minimal state machine.
--
-- ### Sequencing
--
-- This migration lands the SCHEMA only. Kotlin entities +
-- repository + read/write stacks land alongside in the same
-- Chunk D commit (enforced by the backend code referencing
-- the JPA entity at startup).
--
-- ### Scope (NORMATIVE for 4C.1)
--
-- Columns present from day one:
--   - status       {PLANNED, IN_PROGRESS, FINISHED, CANCELLED}
--                  VS1 writes `IN_PROGRESS` directly; `PLANNED`
--                  remains a valid state for the future scheduling
--                  slice (Phase 4B).
--   - encounter_class {AMB}
--                  Intentionally narrow. Expansion to EMER / IMP /
--                  HH / VR is tracked as a Phase 4C carry-forward
--                  and requires a pilot-clinic requirement.
--   - started_at / finished_at TIMESTAMPTZ NULL
--                  `started_at` is set by the initial INSERT when
--                  status = IN_PROGRESS; `finished_at` remains
--                  NULL until a later Phase 4C slice lands the
--                  state-transition surface.
--
-- Columns DELIBERATELY NOT added in 4C.1 (land in later slices):
--   - primary_provider_id / participant_ids   — provider attribution
--   - appointment_id                           — Phase 4B dependency
--   - location_id                              — future Phase 4C surface
--   - entered_in_error flag / amendment chain  — clinical-notes-style
--                                                immutability not
--                                                needed until 4D.
--
-- ### RLS contract (NORMATIVE — mirrors V14 patient pattern)
--
-- Both-GUCs requirement: every SELECT / INSERT / UPDATE / DELETE
-- policy keys on BOTH `app.current_tenant_id` AND
-- `app.current_user_id`. Missing either GUC fails closed
-- (NULLIF(..., '') → NULL; NULL comparisons → UNKNOWN; rows
-- filtered). Defends against any code path bypassing the
-- 4A.0 `PhiRequestContextFilter` / `PhiRlsTxHook`.
--
-- INSERT + UPDATE + DELETE additionally require OWNER or ADMIN
-- membership — mirrors the V14 `ENCOUNTER_WRITE` gate in Kotlin
-- policy. RLS is defense-in-depth; the primary gate is
-- `StartEncounterPolicy.check`.
--
-- ### CANCELLED visibility
--
-- CANCELLED encounters remain visible (unlike patient status
-- DELETED). Clinical history depends on being able to see
-- previously-cancelled visits. No status-based exclusion on the
-- SELECT policy.
--
-- ### Enum encoding
--
-- TEXT + CHECK rather than native PostgreSQL ENUM, per the V5 /
-- V7 / V14 precedent. Consistency across the codebase; a
-- cross-cutting ENUM normalization would be a separate ADR.
--
-- ### Rollback
--
-- `DROP TABLE clinical.encounter;` is safe at 4C.1 scale (no
-- encounter records in production). Once 4C ships real
-- encounter rows, rollback becomes restricted per ADR-001 §7.

-- -----------------------------------------------------------------------
-- clinical.encounter
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS clinical.encounter (
    id                        UUID         NOT NULL,
    tenant_id                 UUID         NOT NULL,
    patient_id                UUID         NOT NULL,

    -- FHIR Encounter.status (narrowed to Medcore's minimal set).
    status                    TEXT         NOT NULL DEFAULT 'IN_PROGRESS',

    -- FHIR Encounter.class (narrowed to AMB for 4C.1).
    encounter_class           TEXT         NOT NULL DEFAULT 'AMB',

    -- Lifecycle timestamps.
    started_at                TIMESTAMPTZ,
    finished_at               TIMESTAMPTZ,

    -- Audit columns (ADR-001 §2; bare UUIDs across modules).
    created_at                TIMESTAMPTZ  NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    created_by                UUID         NOT NULL,
    updated_by                UUID         NOT NULL,
    row_version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_clinical_encounter
        PRIMARY KEY (id),
    CONSTRAINT fk_clinical_encounter_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant (id),
    CONSTRAINT fk_clinical_encounter_patient
        FOREIGN KEY (patient_id) REFERENCES clinical.patient (id),
    CONSTRAINT ck_clinical_encounter_status
        CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'FINISHED', 'CANCELLED')),
    CONSTRAINT ck_clinical_encounter_class
        CHECK (encounter_class IN ('AMB')),
    CONSTRAINT ck_clinical_encounter_lifecycle_coherence
        CHECK (
            -- IN_PROGRESS / FINISHED imply started_at is set.
            (status IN ('PLANNED') AND started_at IS NULL AND finished_at IS NULL)
            OR
            (status = 'IN_PROGRESS' AND started_at IS NOT NULL AND finished_at IS NULL)
            OR
            (status = 'FINISHED' AND started_at IS NOT NULL AND finished_at IS NOT NULL)
            OR
            -- CANCELLED may occur at any lifecycle stage; both
            -- timestamps may be any combination of NULL / NOT NULL.
            (status = 'CANCELLED')
        )
);

-- -----------------------------------------------------------------------
-- Indexes
-- -----------------------------------------------------------------------

-- Primary tenant-scoped lookup for an encounter by id — matches
-- how `GetEncounterHandler` + the RLS envelope resolve a row.
CREATE INDEX IF NOT EXISTS ix_clinical_encounter_tenant
    ON clinical.encounter (tenant_id);

-- Patient timeline: all encounters for a patient, newest first.
-- Anticipates the later Phase 4C slice that ships the
-- patient-timeline read surface.
CREATE INDEX IF NOT EXISTS ix_clinical_encounter_tenant_patient_created
    ON clinical.encounter (tenant_id, patient_id, created_at DESC);

-- Status lookup — "how many in-progress encounters does this
-- tenant have right now?" — supports future dashboard slices.
CREATE INDEX IF NOT EXISTS ix_clinical_encounter_tenant_status
    ON clinical.encounter (tenant_id, status);

-- -----------------------------------------------------------------------
-- Row-level security
-- -----------------------------------------------------------------------
ALTER TABLE clinical.encounter ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinical.encounter FORCE  ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE, DELETE ON clinical.encounter TO medcore_app;

-- ---- encounter SELECT ----
-- Both-GUCs + membership. Unlike patients (which hides DELETED),
-- encounters expose every status — clinical history depends on
-- CANCELLED visibility.
DROP POLICY IF EXISTS p_encounter_select ON clinical.encounter;
CREATE POLICY p_encounter_select
    ON clinical.encounter
    FOR SELECT
    TO medcore_app
    USING (
        clinical.encounter.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.encounter.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
        )
    );

-- ---- encounter INSERT / UPDATE / DELETE (OWNER or ADMIN) ----
DROP POLICY IF EXISTS p_encounter_insert ON clinical.encounter;
CREATE POLICY p_encounter_insert
    ON clinical.encounter
    FOR INSERT
    TO medcore_app
    WITH CHECK (
        clinical.encounter.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.encounter.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

DROP POLICY IF EXISTS p_encounter_update ON clinical.encounter;
CREATE POLICY p_encounter_update
    ON clinical.encounter
    FOR UPDATE
    TO medcore_app
    USING (
        clinical.encounter.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.encounter.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    )
    WITH CHECK (
        clinical.encounter.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.encounter.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

DROP POLICY IF EXISTS p_encounter_delete ON clinical.encounter;
CREATE POLICY p_encounter_delete
    ON clinical.encounter
    FOR DELETE
    TO medcore_app
    USING (
        clinical.encounter.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.encounter.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );
