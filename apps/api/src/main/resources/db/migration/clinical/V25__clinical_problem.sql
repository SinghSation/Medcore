-- V25__clinical_problem.sql — Phase 4E.2
--
-- Patient-level longitudinal problem list. Mirrors the
-- 4E.1 V24 allergy shape — same denormalized tenant_id,
-- same RLS contract, same composite index, same audit-
-- column convention. Differences from V24 are clinical,
-- not structural:
--
--   - `condition_text` (not `substance_text`).
--   - `severity` is NULLABLE (FHIR-aligned: many problems
--     have no clinically meaningful severity, e.g. "history
--     of appendectomy"). 4E.1 allergies require severity;
--     4E.2 problems do not.
--   - `status` enum adds `RESOLVED`. RESOLVED ≠ INACTIVE:
--     INACTIVE = condition exists but currently dormant
--     (chronic flare/quiesce model); RESOLVED = condition
--     no longer exists (cure / one-off illness recovered).
--     This distinction is enforced in audit token names,
--     UI labels, and tests — NOT just here.
--   - `abatement_date` column added (the "ended on" date,
--     paired with `onset_date`). Both optional.
--   - `code_value` / `code_system` reserved for ICD-10 /
--     SNOMED in 5A (same future-proofing pattern as V24's
--     substance_code / substance_system).
--
-- ### Scope (NORMATIVE for 4E.2)
--
-- Locked decisions (per planning Q1–Q8):
--   - Condition is FREE-TEXT today (`condition_text NOT NULL`).
--     Schema reserves `code_value` + `code_system` (both
--     NULL today) for Phase 5A FHIR + Phase 3M reference
--     data without a structural migration.
--   - Severity is NULLABLE — `MILD / MODERATE / SEVERE`
--     when present. No `LIFE_THREATENING` (that is an
--     allergy criticality concept, not a problem severity
--     concept; FHIR keeps these axes separate too).
--   - Status is `ACTIVE / INACTIVE / RESOLVED /
--     ENTERED_IN_ERROR`. Soft-delete only — no row
--     deletion path. ENTERED_IN_ERROR is terminal
--     (transition rule enforced in the handler; PRE-image
--     state, application-owned).
--   - Single free-text `condition_text` (no manifestation
--     decomposition). Coded structure is a 5A FHIR slice.
--   - Optional `onset_date DATE NULL` and `abatement_date
--     DATE NULL` — clinically useful, trivially cheap.
--     FHIR's multi-type onset (Age / Period / Range)
--     deferred to 5A.
--   - Optional `recorded_in_encounter_id UUID NULL` —
--     soft provenance link (FK with ON DELETE SET NULL).
--     Same shape as V24 allergy. Resolution-encounter
--     linkage (which encounter resolved a problem) is
--     deferred to a future slice.
--
-- Deliberately NOT in 4E.2:
--   - Drug-condition / problem-allergy interaction checking
--     (CDS / 7+).
--   - Coded condition values (ICD-10 / SNOMED-CT) — 5A + 3M.
--   - FHIR Condition wire surface — 5A.
--   - Split clinicalStatus + verificationStatus axes
--     (`unconfirmed / provisional / confirmed / refuted`)
--     — deferred to 5A; the combined `status` column
--     above covers clinicalStatus + the
--     `entered-in-error` token from verificationStatus
--     for now.
--   - Family history (FHIR FamilyMemberHistory) — separate
--     slice.
--   - Goals / care plans tied to problems — later phase.
--
-- ### Indexes
--
-- Primary read pattern is "list all problems for one
-- patient, with the chart-context filter `WHERE status IN
-- ('ACTIVE')` (or arbitrary subsets for the management
-- view)." Composite `(tenant_id, patient_id, status)`
-- covers both queries via leading-prefix scan, mirroring
-- V24.
--
-- ### RLS contract (NORMATIVE — mirrors V24 / V19)
--
-- Both-GUCs requirement: every policy keys on BOTH
-- `app.current_tenant_id` AND `app.current_user_id`.
-- Missing either GUC fails closed.
--
-- SELECT — every active tenant member (any role) can read.
-- INSERT / UPDATE — OWNER or ADMIN only (mirrors the
-- PROBLEM_WRITE Kotlin authority gate, defined in chunk B).
-- DELETE — same OWNER/ADMIN gate as defense-in-depth, even
-- though the application never DELETEs (soft-delete via
-- status). Future operational cleanup goes through a
-- SECURITY DEFINER admin function, not row-level DELETE.
--
-- ### Denormalized tenant_id
--
-- Same pattern as V14 patient / V18 encounter / V19 note
-- / V24 allergy: tenant_id on the row keeps the RLS
-- policy a simple single-table predicate. Handler-side
-- invariant: problem.tenant_id = patient.tenant_id at
-- INSERT time. No DB-level CHECK on the cross-table
-- relationship — the patient FK + RLS policy give us
-- the guarantee operationally.
--
-- ### Rollback
--
-- `DROP TABLE clinical.problem;` is safe at 4E.2 scale
-- (no production rows). Reversible.

-- -----------------------------------------------------------------------
-- clinical.problem
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS clinical.problem (
    id                          UUID         NOT NULL,
    tenant_id                   UUID         NOT NULL,
    patient_id                  UUID         NOT NULL,

    -- Condition: free-text today, future-proofed for coded values.
    condition_text              TEXT         NOT NULL,
    code_value                  TEXT         NULL,
    code_system                 TEXT         NULL,

    -- Closed-enum clinical fields.
    -- Severity is NULLABLE per locked Q3 (FHIR-aligned: many
    -- problems have no clinically meaningful severity).
    severity                    TEXT         NULL,
    status                      TEXT         NOT NULL DEFAULT 'ACTIVE',

    -- Optional clinical metadata.
    onset_date                  DATE         NULL,
    abatement_date              DATE         NULL,
    recorded_in_encounter_id    UUID         NULL,

    -- Audit columns (standard pattern).
    created_at                  TIMESTAMPTZ  NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL,
    created_by                  UUID         NOT NULL,
    updated_by                  UUID         NOT NULL,
    row_version                 BIGINT       NOT NULL DEFAULT 0,

    -- Platform soft-delete column (per .cursor/rules/04 PHI-table
    -- baseline). 4E.2 ships with NO write path that populates it
    -- — clinical lifecycle uses `status` (ACTIVE / INACTIVE /
    -- RESOLVED / ENTERED_IN_ERROR). `deleted_at` is a separate
    -- axis reserved for future operational redaction (e.g.
    -- "wrong-patient PHI must be removed without exposing the
    -- clinical 'this was a mistake' state in the normal chart
    -- view"). Future cross-table retention / cleanup tooling
    -- that filters by `deleted_at IS NULL` will work additively
    -- when that slice lands. Included from day one per 4E.1
    -- learnings (CodeRabbit Major last time was the absence of
    -- this column).
    deleted_at                  TIMESTAMPTZ  NULL,

    CONSTRAINT pk_clinical_problem
        PRIMARY KEY (id),
    CONSTRAINT fk_clinical_problem_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant (id),
    CONSTRAINT fk_clinical_problem_patient
        FOREIGN KEY (patient_id) REFERENCES clinical.patient (id),
    CONSTRAINT fk_clinical_problem_recorded_encounter
        FOREIGN KEY (recorded_in_encounter_id)
        REFERENCES clinical.encounter (id)
        ON DELETE SET NULL,

    -- Closed-enum guard rails (matches Kotlin enums; renaming
    -- a token is a contract change and requires a superseding
    -- ADR per the registry-discipline rule on AuditAction).
    -- Severity is nullable, so the CHECK admits NULL.
    CONSTRAINT ck_clinical_problem_severity
        CHECK (
            severity IS NULL
            OR severity IN ('MILD', 'MODERATE', 'SEVERE')
        ),
    -- RESOLVED ≠ INACTIVE — see the file-level KDoc above
    -- and chunk B audit tokens. This CHECK guarantees only
    -- the four known tokens land in the column; the handler
    -- enforces the legal transition graph between them.
    CONSTRAINT ck_clinical_problem_status
        CHECK (status IN (
            'ACTIVE', 'INACTIVE', 'RESOLVED', 'ENTERED_IN_ERROR'
        )),

    -- Condition text discipline: trim-nonempty + bounded.
    -- Both checks key on `btrim()` so a whitespace-only
    -- value cannot land via direct SQL even if the
    -- application validator is bypassed (RLS-only path,
    -- future direct backfill, or a Kotlin validator
    -- regression). 500 chars matches the V24 substance
    -- bound — generous for compound descriptions
    -- ("Type 2 diabetes mellitus with diabetic peripheral
    -- neuropathy"). Included from day one per 4E.1
    -- learnings (CodeRabbit Minor).
    CONSTRAINT ck_clinical_problem_condition_text_length
        CHECK (
            char_length(btrim(condition_text)) >= 1
            AND char_length(condition_text) <= 500
        ),

    -- Coding coherence: code and system are populated together
    -- or both null. Prevents "code without source ontology"
    -- ambiguity when 3M/5A lights this up. Same shape as
    -- V24's substance coding coherence CHECK.
    CONSTRAINT ck_clinical_problem_code_coherent
        CHECK (
            (code_value IS NULL AND code_system IS NULL)
            OR (code_value IS NOT NULL AND code_system IS NOT NULL)
        ),

    -- Date ordering: when both onset and abatement are
    -- present, abatement cannot precede onset. Permits NULL
    -- on either side. Cheap structural invariant; clinically
    -- meaningless to record "ended before it started".
    CONSTRAINT ck_clinical_problem_abatement_after_onset
        CHECK (
            onset_date IS NULL
            OR abatement_date IS NULL
            OR abatement_date >= onset_date
        )
);

-- -----------------------------------------------------------------------
-- Indexes
-- -----------------------------------------------------------------------
-- Chart-context read pattern: "give me all problems for
-- this patient, optionally filtered by status." Composite
-- index serves both the unfiltered list and any
-- single-status drill-down via leading-prefix scan.
-- Same shape as V24's allergy banner index.
CREATE INDEX IF NOT EXISTS ix_clinical_problem_tenant_patient_status
    ON clinical.problem (tenant_id, patient_id, status);

-- -----------------------------------------------------------------------
-- Row-level security
-- -----------------------------------------------------------------------
ALTER TABLE clinical.problem ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinical.problem FORCE  ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE, DELETE ON clinical.problem TO medcore_app;

-- ---- problem SELECT (every active tenant member) ----
DROP POLICY IF EXISTS p_problem_select ON clinical.problem;
CREATE POLICY p_problem_select
    ON clinical.problem
    FOR SELECT
    TO medcore_app
    USING (
        clinical.problem.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.problem.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
        )
    );

-- ---- problem INSERT / UPDATE / DELETE (OWNER or ADMIN) ----
DROP POLICY IF EXISTS p_problem_insert ON clinical.problem;
CREATE POLICY p_problem_insert
    ON clinical.problem
    FOR INSERT
    TO medcore_app
    WITH CHECK (
        clinical.problem.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.problem.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

DROP POLICY IF EXISTS p_problem_update ON clinical.problem;
CREATE POLICY p_problem_update
    ON clinical.problem
    FOR UPDATE
    TO medcore_app
    USING (
        clinical.problem.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.problem.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    )
    WITH CHECK (
        clinical.problem.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.problem.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

DROP POLICY IF EXISTS p_problem_delete ON clinical.problem;
CREATE POLICY p_problem_delete
    ON clinical.problem
    FOR DELETE
    TO medcore_app
    USING (
        clinical.problem.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.problem.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );
