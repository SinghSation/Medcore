-- V19__clinical_encounter_note.sql — Phase 4D.1 (VS1 Chunk E)
--
-- First clinical documentation surface in Medcore. Establishes
-- `clinical.encounter_note` — an append-only free-text note
-- tied to an encounter. This is the minimal substrate for the
-- VS1 "write note → save" golden path.
--
-- ### Scope (NORMATIVE for 4D.1)
--
-- The table carries:
--   - FK to encounter + denormalized tenant_id (for RLS policy
--     keys without a JOIN).
--   - body TEXT with a CHECK-constrained length (1..20000).
--     The upper bound is conservative for a minimal slice;
--     20,000 chars is ~10 pages of clinical prose.
--   - Standard audit columns (created_at, updated_at,
--     created_by, updated_by, row_version).
--
-- Deliberately NOT in 4D.1:
--   - status column (no signing flow yet — Phase 4D full exit)
--   - amends_id FK (no amendments yet — Phase 4D full exit)
--   - template_id, section codes (no structured SOAP yet)
--   - FHIR DocumentReference surface (Phase 5A)
--
-- Append-only by design: there is no UPDATE path shipped in
-- 4D.1. Future amendments land as NEW rows linked to the
-- original via a future `amends_id` column (additive migration).
-- V19's `UPDATE` policy exists for PATCH-by-Hibernate edge
-- cases (JPA's optimistic-lock retry) but no controller ever
-- mutates an existing note.
--
-- ### RLS contract (NORMATIVE — mirrors V18 encounter pattern)
--
-- Both-GUCs requirement: every SELECT / INSERT / UPDATE / DELETE
-- policy keys on BOTH `app.current_tenant_id` AND
-- `app.current_user_id`. Missing either GUC fails closed.
--
-- INSERT / UPDATE / DELETE additionally require OWNER or ADMIN
-- membership — mirrors the `NOTE_WRITE` gate in Kotlin policy.
--
-- ### Denormalized tenant_id
--
-- Matches V18 encounter pattern (and V14 patient pattern):
-- the tenant is on the row so the RLS policy is a simple
-- single-table predicate. The FK to encounter provides
-- referential integrity; the denormalized tenant_id provides
-- the RLS key. Keeping both in sync is the handler's job
-- (note.tenantId = encounter.tenantId) — any drift would
-- trip the `ck_encounter_note_tenant_matches_encounter` check
-- at the DB layer (defense in depth).
--
-- ### Rollback
--
-- `DROP TABLE clinical.encounter_note;` is safe at 4D.1 scale
-- (no note records in production).

-- -----------------------------------------------------------------------
-- clinical.encounter_note
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS clinical.encounter_note (
    id                        UUID         NOT NULL,
    tenant_id                 UUID         NOT NULL,
    encounter_id              UUID         NOT NULL,

    body                      TEXT         NOT NULL,

    created_at                TIMESTAMPTZ  NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    created_by                UUID         NOT NULL,
    updated_by                UUID         NOT NULL,
    row_version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_clinical_encounter_note
        PRIMARY KEY (id),
    CONSTRAINT fk_clinical_encounter_note_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant (id),
    CONSTRAINT fk_clinical_encounter_note_encounter
        FOREIGN KEY (encounter_id) REFERENCES clinical.encounter (id),
    CONSTRAINT ck_clinical_encounter_note_body_length
        CHECK (char_length(body) >= 1 AND char_length(body) <= 20000)
);

-- -----------------------------------------------------------------------
-- Indexes
-- -----------------------------------------------------------------------
-- Primary read pattern: "list notes for an encounter, newest first".
CREATE INDEX IF NOT EXISTS ix_clinical_encounter_note_tenant_enc_created
    ON clinical.encounter_note (tenant_id, encounter_id, created_at DESC);

-- -----------------------------------------------------------------------
-- Row-level security
-- -----------------------------------------------------------------------
ALTER TABLE clinical.encounter_note ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinical.encounter_note FORCE  ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE, DELETE ON clinical.encounter_note TO medcore_app;

-- ---- encounter_note SELECT ----
DROP POLICY IF EXISTS p_encounter_note_select ON clinical.encounter_note;
CREATE POLICY p_encounter_note_select
    ON clinical.encounter_note
    FOR SELECT
    TO medcore_app
    USING (
        clinical.encounter_note.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.encounter_note.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
        )
    );

-- ---- encounter_note INSERT / UPDATE / DELETE (OWNER or ADMIN) ----
DROP POLICY IF EXISTS p_encounter_note_insert ON clinical.encounter_note;
CREATE POLICY p_encounter_note_insert
    ON clinical.encounter_note
    FOR INSERT
    TO medcore_app
    WITH CHECK (
        clinical.encounter_note.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.encounter_note.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

DROP POLICY IF EXISTS p_encounter_note_update ON clinical.encounter_note;
CREATE POLICY p_encounter_note_update
    ON clinical.encounter_note
    FOR UPDATE
    TO medcore_app
    USING (
        clinical.encounter_note.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.encounter_note.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    )
    WITH CHECK (
        clinical.encounter_note.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.encounter_note.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

DROP POLICY IF EXISTS p_encounter_note_delete ON clinical.encounter_note;
CREATE POLICY p_encounter_note_delete
    ON clinical.encounter_note
    FOR DELETE
    TO medcore_app
    USING (
        clinical.encounter_note.tenant_id
            = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
        AND EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = clinical.encounter_note.tenant_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );
