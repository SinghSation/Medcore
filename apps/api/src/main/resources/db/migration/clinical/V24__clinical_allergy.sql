-- V24__clinical_allergy.sql — Phase 4E.1
--
-- First longitudinal patient-level clinical dataset in Medcore.
-- Allergies live on the patient — not the encounter — so they
-- surface across every encounter view as a clinical-safety
-- banner. Same RLS shape as V19 encounter_note (both-GUCs +
-- OWNER/ADMIN write gate), same audit-column convention as
-- V18 encounter and V14 patient.
--
-- ### Scope (NORMATIVE for 4E.1)
--
-- Locked decisions:
--   - Substance is FREE-TEXT today (`substance_text NOT NULL`).
--     The schema reserves `substance_code` + `substance_system`
--     (both NULL today) so Phase 5A FHIR + Phase 3M reference
--     data can attach RxNorm / SNOMED codes without a structural
--     migration.
--   - Severity is the FHIR-aligned criticality enum (MILD /
--     MODERATE / SEVERE / LIFE_THREATENING).
--   - Status is ACTIVE / INACTIVE / ENTERED_IN_ERROR. Soft-
--     delete only — no row deletion path. ENTERED_IN_ERROR is
--     terminal (status transition rule enforced in the handler;
--     not a DB trigger because the rule depends on PRE-image
--     state, which the application owns).
--   - Single free-text `reaction_text` (no manifestation /
--     onset_severity decomposition). Coded reactions are a
--     5A FHIR slice.
--   - Optional `onset_date DATE NULL` — clinically useful,
--     trivially cheap.
--   - Optional `recorded_in_encounter_id UUID NULL` — soft
--     provenance link (FK with ON DELETE SET NULL). NOT a
--     hard requirement; allergies recorded outside an
--     encounter (e.g. future intake-form path) leave it null.
--
-- Deliberately NOT in 4E.1:
--   - Drug-drug / drug-allergy interaction checking (CDS / 7+).
--   - No-known-allergies (NKA) sentinel — separate slice.
--   - Coded substance values — 5A + 3M.
--   - FHIR AllergyIntolerance wire surface — 5A.
--   - Verification status (FHIR's verificationStatus axis) —
--     deferred to 5A; the combined `status` column above
--     covers clinicalStatus + a subset of verificationStatus
--     ('entered-in-error') for now.
--
-- ### Indexes
--
-- Primary read pattern is "list all allergies for one patient,
-- with the banner filter `WHERE status = 'ACTIVE'`." The
-- composite `(tenant_id, patient_id, status)` index covers both
-- the banner query and the management-view "all statuses for
-- one patient" query.
--
-- ### RLS contract (NORMATIVE — mirrors V19 encounter_note)
--
-- Both-GUCs requirement: every policy keys on BOTH
-- `app.current_tenant_id` AND `app.current_user_id`. Missing
-- either GUC fails closed.
--
-- SELECT — every active tenant member (any role) can read.
-- INSERT / UPDATE — OWNER or ADMIN only (mirrors the
-- ALLERGY_WRITE Kotlin authority gate).
-- DELETE — same OWNER/ADMIN gate as a defense-in-depth, even
-- though the application never DELETEs (soft-delete via
-- status). Future operational cleanup (retention) goes through
-- a SECURITY DEFINER admin function, not a row-level DELETE.
--
-- ### Denormalized tenant_id
--
-- Same pattern as V14 patient / V18 encounter / V19 note:
-- tenant_id on the row keeps the RLS policy a simple single-
-- table predicate. Handler-side invariant: allergy.tenant_id
-- = patient.tenant_id at INSERT time. No DB-level CHECK on
-- the cross-table relationship — the patient FK + RLS policy
-- give us the guarantee operationally.
--
-- ### Rollback
--
-- `DROP TABLE clinical.allergy;` is safe at 4E.1 scale (no
-- production rows). Reversible.

-- -----------------------------------------------------------------------
-- clinical.allergy
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS clinical.allergy (
    id                          UUID         NOT NULL,
    tenant_id                   UUID         NOT NULL,
    patient_id                  UUID         NOT NULL,

    -- Substance: free-text today, future-proofed for coded values.
    substance_text              TEXT         NOT NULL,
    substance_code              TEXT         NULL,
    substance_system            TEXT         NULL,

    -- Closed-enum clinical fields.
    severity                    TEXT         NOT NULL,
    status                      TEXT         NOT NULL DEFAULT 'ACTIVE',

    -- Optional clinical metadata.
    reaction_text               TEXT         NULL,
    onset_date                  DATE         NULL,
    recorded_in_encounter_id    UUID         NULL,

    -- Audit columns (standard pattern).
    created_at                  TIMESTAMPTZ  NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL,
    created_by                  UUID         NOT NULL,
    updated_by                  UUID         NOT NULL,
    row_version                 BIGINT       NOT NULL DEFAULT 0,

    -- Platform soft-delete column (per .cursor/rules/04 PHI-table
    -- baseline). 4E.1 ships with NO write path that populates it
    -- — clinical lifecycle uses `status` (ACTIVE / INACTIVE /
    -- ENTERED_IN_ERROR) per the locked Q1 decision. `deleted_at`
    -- is a separate axis reserved for future operational
    -- redaction (e.g., "wrong-patient PHI must be removed without
    -- exposing the clinical 'this was a mistake' state in the
    -- normal banner"). Future cross-table retention / cleanup
    -- tooling that filters by `deleted_at IS NULL` will work
    -- additively when that slice lands.
    deleted_at                  TIMESTAMPTZ  NULL,

    CONSTRAINT pk_clinical_allergy
        PRIMARY KEY (id),
    CONSTRAINT fk_clinical_allergy_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant (id),
    CONSTRAINT fk_clinical_allergy_patient
        FOREIGN KEY (patient_id) REFERENCES clinical.patient (id),
    CONSTRAINT fk_clinical_allergy_recorded_encounter
        FOREIGN KEY (recorded_in_encounter_id)
        REFERENCES clinical.encounter (id)
        ON DELETE SET NULL,

    -- Closed-enum guard rails (matches Kotlin enums; renaming
    -- a token is a contract change and requires a superseding
    -- ADR per the registry-discipline rule on AuditAction).
    CONSTRAINT ck_clinical_allergy_severity
        CHECK (severity IN (
            'MILD', 'MODERATE', 'SEVERE', 'LIFE_THREATENING'
        )),
    CONSTRAINT ck_clinical_allergy_status
        CHECK (status IN (
            'ACTIVE', 'INACTIVE', 'ENTERED_IN_ERROR'
        )),

    -- Substance + reaction text discipline: trim-nonempty +
    -- bounded. Both checks key on `btrim()` so a whitespace-only
    -- value cannot land via direct SQL even if the application
    -- validator is bypassed (RLS-only path, future direct backfill,
    -- or a Kotlin validator regression). Substance names are
    -- short ("Penicillin", "Tree nuts"); 500 chars is generous
    -- for compound descriptions ("Iodinated contrast dye, oral
    -- preparation"). Reaction is much longer because it is prose
    -- ("hives + facial swelling, resolved with antihistamines").
    CONSTRAINT ck_clinical_allergy_substance_text_length
        CHECK (
            char_length(btrim(substance_text)) >= 1
            AND char_length(substance_text) <= 500
        ),
    CONSTRAINT ck_clinical_allergy_reaction_text_length
        CHECK (
            reaction_text IS NULL
            OR char_length(btrim(reaction_text)) <= 4000
        ),

    -- Coding coherence: code and system are populated together
    -- or both null. Prevents "code without source ontology"
    -- ambiguity when 3M/5A lights this up.
    CONSTRAINT ck_clinical_allergy_substance_coding_coherent
        CHECK (
            (substance_code IS NULL AND substance_system IS NULL)
            OR (substance_code IS NOT NULL AND substance_system IS NOT NULL)
        )
);

-- -----------------------------------------------------------------------
-- Indexes
-- -----------------------------------------------------------------------
-- Banner read pattern: "give me ACTIVE allergies for this
-- patient." Management view: "give me ALL allergies for this
-- patient regardless of status." Composite index serves both
-- (PG can use a leading-prefix scan).
CREATE INDEX IF NOT EXISTS ix_clinical_allergy_tenant_patient_status
    ON clinical.allergy (tenant_id, patient_id, status);

-- -----------------------------------------------------------------------
-- Row-level security
-- -----------------------------------------------------------------------
ALTER TABLE clinical.allergy ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinical.allergy FORCE  ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE, DELETE ON clinical.allergy TO medcore_app;

-- ---- allergy SELECT (every active tenant member) ----
DROP POLICY IF EXISTS p_allergy_select ON clinical.allergy;
CREATE POLICY p_allergy_select
    ON clinical.allergy
    FOR SELECT
    TO medcore_app
    USING (
        clinical.allergy.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.allergy.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
        )
    );

-- ---- allergy INSERT / UPDATE / DELETE (OWNER or ADMIN) ----
DROP POLICY IF EXISTS p_allergy_insert ON clinical.allergy;
CREATE POLICY p_allergy_insert
    ON clinical.allergy
    FOR INSERT
    TO medcore_app
    WITH CHECK (
        clinical.allergy.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.allergy.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

DROP POLICY IF EXISTS p_allergy_update ON clinical.allergy;
CREATE POLICY p_allergy_update
    ON clinical.allergy
    FOR UPDATE
    TO medcore_app
    USING (
        clinical.allergy.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.allergy.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    )
    WITH CHECK (
        clinical.allergy.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.allergy.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

DROP POLICY IF EXISTS p_allergy_delete ON clinical.allergy;
CREATE POLICY p_allergy_delete
    ON clinical.allergy
    FOR DELETE
    TO medcore_app
    USING (
        clinical.allergy.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.allergy.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );
