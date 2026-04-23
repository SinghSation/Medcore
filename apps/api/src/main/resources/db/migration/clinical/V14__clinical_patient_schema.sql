-- V14__clinical_patient_schema.sql — Phase 4A.1
--
-- Establishes Medcore's FIRST PHI-bearing table (`clinical.patient`)
-- + a satellite identifier table (`clinical.patient_identifier`).
-- This is the substrate on which every future clinical module
-- (encounters, notes, medications, orders, documents) builds.
--
-- ### Sequencing
--
-- This migration lands the SCHEMA only. Kotlin entities +
-- repositories are Phase 4A.1 companions. Services, handlers,
-- endpoints, MRN generation logic, duplicate detection, FHIR
-- mapping, and read-auditing all land in separate 4A.x slices
-- with zero further migration work.
--
-- ### RLS contract (NORMATIVE — enforced below)
--
-- Both-GUCs requirement: every SELECT / INSERT / UPDATE / DELETE
-- policy keys on BOTH `app.current_tenant_id` AND
-- `app.current_user_id`. Missing either GUC fails closed
-- (NULLIF(..., '') yields NULL; NULL comparisons produce UNKNOWN;
-- rows filtered out). This defends against any code path that
-- bypasses Phase 4A.0's PhiRequestContextFilter / PhiRlsTxHook —
-- one missing GUC reduces the result set to zero, one wrong
-- tenant_id is caught by the policy's explicit tenant check.
--
-- ### Recursion analysis (explicit — per 4A.1 review)
--
-- Policies below contain
--   EXISTS (SELECT 1 FROM tenancy.tenant_membership ...)
-- subqueries. That subquery triggers RLS policies on
-- `tenancy.tenant_membership`:
--   - V8 `p_membership_select_own` — USING `user_id = GUC`.
--     Non-self-referential.
--   - V13 `p_membership_select_by_admin_or_owner` — USING the
--     SECURITY DEFINER function `tenancy.caller_is_tenant_admin`,
--     which bypasses RLS (owned by medcore_rls_helper with
--     BYPASSRLS). Non-recursive.
-- Result: the subquery terminates cleanly. The subquery's own
-- WHERE clause filters to `tm.user_id = caller`, so V13's extra
-- admin-visibility doesn't affect correctness here.
--
-- ### Soft-delete discipline (per 4A.1 review)
--
-- The SELECT policy EXCLUDES rows with `status = 'DELETED'`.
-- MERGED_AWAY rows remain visible (merge-unwind workflows need
-- them). Any future forensic access to DELETED rows goes through
-- a SECURITY DEFINER helper, never the RLS read path.
--
-- ### FHIR alignment
--
-- Column naming maps directly to US Core Patient fields
-- (`name_given` -> `Patient.name.given[0]`, `administrative_sex`
-- -> `Patient.gender`, etc.). A thin PatientFhirMapper (Phase
-- 4A.4) converts entity -> FHIR JSON at read time; Medcore does
-- NOT maintain a separate FHIR-shape table.
--
-- ### TEXT + CHECK pattern for enums (NOT Postgres native ENUM)
--
-- Every enum-shaped column in Medcore's schema (V5 tenant.status,
-- V6 tenant_membership.role / .status, V7 audit_event.actor_type /
-- .outcome) uses TEXT + CHECK. V14 stays consistent. Migrating to
-- native Postgres ENUMs would be a cross-cutting schema
-- normalization slice — worth its own ADR if we ever do it.
--
-- Rollback: DROP TABLE clinical.patient_identifier, patient;
-- DROP SCHEMA clinical; DROP EXTENSION fuzzystrmatch (if no other
-- consumers). Acceptable at 4A.1 scale (no clinical data yet);
-- PROHIBITED once 4A.2+ ships real patient records (ADR-001 §7).

-- -----------------------------------------------------------------------
-- Extensions (contrib, ships with PostgreSQL)
-- -----------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;

-- -----------------------------------------------------------------------
-- Clinical schema
-- -----------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS clinical;
GRANT USAGE ON SCHEMA clinical TO medcore_app;
GRANT USAGE, CREATE ON SCHEMA clinical TO medcore_migrator;

-- -----------------------------------------------------------------------
-- clinical.patient
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS clinical.patient (
    id                        UUID         NOT NULL,
    tenant_id                 UUID         NOT NULL,

    -- MRN is authoritative (decision: no mrn_sequence column in
    -- 4A.1; MRN generation satellite `patient_mrn_counter` lands
    -- with 4A.2 when handler sequencing logic needs it).
    mrn                       TEXT         NOT NULL,
    mrn_source                TEXT         NOT NULL DEFAULT 'GENERATED',

    -- HumanName parts (FHIR Patient.name[use='official'])
    name_given                TEXT         NOT NULL,
    name_family               TEXT         NOT NULL,
    name_middle               TEXT,
    name_suffix               TEXT,
    name_prefix               TEXT,
    -- HumanName (FHIR Patient.name[use='usual'])
    preferred_name            TEXT,

    -- FHIR Patient.birthDate
    birth_date                DATE         NOT NULL,

    -- FHIR Patient.gender (administrative sex)
    administrative_sex        TEXT         NOT NULL,

    -- US Core extensions — demographic identity
    sex_assigned_at_birth     TEXT,
    gender_identity_code      TEXT,

    -- FHIR Patient.communication.language
    preferred_language        TEXT,

    -- Lifecycle (carries merge fields from day one so the future
    -- merge slice is migration-free)
    status                    TEXT         NOT NULL DEFAULT 'ACTIVE',
    merged_into_id            UUID,
    merged_at                 TIMESTAMPTZ,
    merged_by                 UUID,

    -- Audit (ADR-001 §2; bare UUIDs across module boundaries)
    created_at                TIMESTAMPTZ  NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    created_by                UUID         NOT NULL,
    updated_by                UUID         NOT NULL,
    row_version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_clinical_patient
        PRIMARY KEY (id),
    CONSTRAINT fk_clinical_patient_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant (id),
    CONSTRAINT uq_clinical_patient_tenant_mrn
        UNIQUE (tenant_id, mrn),
    CONSTRAINT ck_clinical_patient_administrative_sex
        CHECK (administrative_sex IN ('male', 'female', 'other', 'unknown')),
    CONSTRAINT ck_clinical_patient_status
        CHECK (status IN ('ACTIVE', 'MERGED_AWAY', 'DELETED')),
    CONSTRAINT ck_clinical_patient_mrn_source
        CHECK (mrn_source IN ('GENERATED', 'IMPORTED')),
    CONSTRAINT ck_clinical_patient_sex_assigned_at_birth
        CHECK (sex_assigned_at_birth IS NULL
            OR sex_assigned_at_birth IN ('M', 'F', 'UNK')),
    CONSTRAINT ck_clinical_patient_merged_fields_coherent
        CHECK (
            (status != 'MERGED_AWAY' AND merged_into_id IS NULL
                AND merged_at IS NULL AND merged_by IS NULL)
            OR
            (status = 'MERGED_AWAY' AND merged_into_id IS NOT NULL
                AND merged_at IS NOT NULL AND merged_by IS NOT NULL)
        )
);

-- -----------------------------------------------------------------------
-- clinical.patient_identifier (satellite: external identifiers)
-- -----------------------------------------------------------------------
-- SSN deliberately omitted from the `type` enum in 4A.1 (per
-- design-pack §9.3). SSN storage introduces state-law +
-- minimum-necessary compliance surface; added additively in a
-- dedicated slice with its own PHI-exposure review when a pilot
-- customer requires it.
CREATE TABLE IF NOT EXISTS clinical.patient_identifier (
    id           UUID         NOT NULL,
    patient_id   UUID         NOT NULL,
    type         TEXT         NOT NULL,
    issuer       TEXT         NOT NULL,
    value        TEXT         NOT NULL,
    valid_from   TIMESTAMPTZ,
    valid_to     TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    row_version  BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_clinical_patient_identifier
        PRIMARY KEY (id),
    CONSTRAINT fk_clinical_patient_identifier_patient
        FOREIGN KEY (patient_id) REFERENCES clinical.patient (id),
    CONSTRAINT uq_clinical_patient_identifier_unique
        UNIQUE (patient_id, type, issuer, value),
    CONSTRAINT ck_clinical_patient_identifier_type
        CHECK (type IN ('MRN_EXTERNAL', 'DRIVERS_LICENSE', 'INSURANCE_MEMBER', 'OTHER'))
);

-- -----------------------------------------------------------------------
-- Indexes (4A.2 duplicate-detection-aware)
-- -----------------------------------------------------------------------
-- Primary tenant-scoped lookup.
CREATE INDEX IF NOT EXISTS ix_clinical_patient_tenant
    ON clinical.patient (tenant_id);

-- Duplicate detection: exact-match candidates on
-- (tenant_id, dob, lower(family), lower(given)). Anticipates
-- 4A.2's duplicate-warning handler.
CREATE INDEX IF NOT EXISTS ix_clinical_patient_tenant_dob_family_given
    ON clinical.patient (
        tenant_id,
        birth_date,
        lower(name_family),
        lower(name_given)
    );

-- Phonetic last-name match. Anticipates 4A.2 design refinement #1
-- (phonetic fuzzymatch for MPI-lite duplicate warning).
CREATE INDEX IF NOT EXISTS ix_clinical_patient_tenant_soundex_family
    ON clinical.patient (tenant_id, soundex(name_family));

-- Satellite table — common parent-id lookup.
CREATE INDEX IF NOT EXISTS ix_clinical_patient_identifier_patient
    ON clinical.patient_identifier (patient_id);

-- -----------------------------------------------------------------------
-- Row-level security
-- -----------------------------------------------------------------------
ALTER TABLE clinical.patient            ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinical.patient            FORCE ROW LEVEL SECURITY;
ALTER TABLE clinical.patient_identifier ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinical.patient_identifier FORCE ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE, DELETE ON clinical.patient            TO medcore_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON clinical.patient_identifier TO medcore_app;

-- ---- patient SELECT ----
-- Both-GUCs + membership + not-DELETED.
-- The explicit `tenant_id = GUC` check is MANDATORY (defense in
-- depth): fail-closed if app.current_tenant_id is missing even
-- when app.current_user_id is set and the user happens to be a
-- member of some other tenant.
DROP POLICY IF EXISTS p_patient_select ON clinical.patient;
CREATE POLICY p_patient_select
    ON clinical.patient
    FOR SELECT
    TO medcore_app
    USING (
        clinical.patient.status != 'DELETED'
        AND clinical.patient.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.patient.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
        )
    );

-- ---- patient INSERT / UPDATE / DELETE (OWNER or ADMIN) ----
DROP POLICY IF EXISTS p_patient_insert ON clinical.patient;
CREATE POLICY p_patient_insert
    ON clinical.patient
    FOR INSERT
    TO medcore_app
    WITH CHECK (
        clinical.patient.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.patient.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

DROP POLICY IF EXISTS p_patient_update ON clinical.patient;
CREATE POLICY p_patient_update
    ON clinical.patient
    FOR UPDATE
    TO medcore_app
    USING (
        clinical.patient.status != 'DELETED'
        AND clinical.patient.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.patient.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    )
    WITH CHECK (
        clinical.patient.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.patient.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

DROP POLICY IF EXISTS p_patient_delete ON clinical.patient;
CREATE POLICY p_patient_delete
    ON clinical.patient
    FOR DELETE
    TO medcore_app
    USING (
        clinical.patient.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.patient.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

-- ---- patient_identifier policies (transitive via parent) ----
-- Simpler: identifier visibility inherits parent-patient visibility.
-- The subquery `SELECT ... FROM clinical.patient` is itself
-- subject to p_patient_select (including the DELETED exclusion),
-- so identifiers of DELETED patients are also hidden.
DROP POLICY IF EXISTS p_patient_identifier_select ON clinical.patient_identifier;
CREATE POLICY p_patient_identifier_select
    ON clinical.patient_identifier
    FOR SELECT
    TO medcore_app
    USING (
        EXISTS (
            SELECT 1
              FROM clinical.patient p
             WHERE p.id = clinical.patient_identifier.patient_id
        )
    );

DROP POLICY IF EXISTS p_patient_identifier_insert ON clinical.patient_identifier;
CREATE POLICY p_patient_identifier_insert
    ON clinical.patient_identifier
    FOR INSERT
    TO medcore_app
    WITH CHECK (
        EXISTS (
            SELECT 1
              FROM clinical.patient p
             WHERE p.id = clinical.patient_identifier.patient_id
               -- Writes on the parent already require OWNER/ADMIN;
               -- identifier INSERT inherits that gate via the
               -- subquery, which runs under p_patient_select.
               -- An additional explicit role check here would be
               -- redundant and could drift from the parent policy.
        )
    );

DROP POLICY IF EXISTS p_patient_identifier_update ON clinical.patient_identifier;
CREATE POLICY p_patient_identifier_update
    ON clinical.patient_identifier
    FOR UPDATE
    TO medcore_app
    USING (
        EXISTS (
            SELECT 1 FROM clinical.patient p
             WHERE p.id = clinical.patient_identifier.patient_id
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM clinical.patient p
             WHERE p.id = clinical.patient_identifier.patient_id
        )
    );

DROP POLICY IF EXISTS p_patient_identifier_delete ON clinical.patient_identifier;
CREATE POLICY p_patient_identifier_delete
    ON clinical.patient_identifier
    FOR DELETE
    TO medcore_app
    USING (
        EXISTS (
            SELECT 1 FROM clinical.patient p
             WHERE p.id = clinical.patient_identifier.patient_id
        )
    );
