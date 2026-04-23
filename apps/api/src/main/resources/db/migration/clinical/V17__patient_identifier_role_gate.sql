-- V17__patient_identifier_role_gate.sql — Phase 4A.3
--
-- Tightens RLS INSERT/UPDATE/DELETE policies on
-- `clinical.patient_identifier` to require the caller's membership
-- role to be OWNER or ADMIN. Matches the role-gate discipline on
-- `clinical.patient`'s write policies (installed in V14) — closes
-- a defense-in-depth gap that pattern-validation for Phase 4A.3
-- surfaced.
--
-- ### What V14 got wrong
--
-- V14 wrote four `patient_identifier` policies (SELECT / INSERT /
-- UPDATE / DELETE), all delegating visibility to the parent via
--     EXISTS (SELECT 1 FROM clinical.patient p
--              WHERE p.id = patient_identifier.patient_id)
-- The subquery runs under `p_patient_select`, which enforces
--   - clinical.patient.status != 'DELETED'
--   - tenant_id = app.current_tenant_id GUC
--   - ACTIVE membership exists for the caller
-- but does NOT enforce OWNER/ADMIN role.
--
-- V14's inline comment read:
--   "Writes on the parent already require OWNER/ADMIN; identifier
--    INSERT inherits that gate via the subquery."
-- That statement was factually wrong. A caller with a MEMBER
-- membership, if they bypassed the application's AuthzPolicy
-- (hypothetically, via a bug or a future code path that reached
-- the repository without going through the WriteGate), could
-- INSERT/UPDATE/DELETE identifiers at the RLS layer.
--
-- Defense-in-depth repair: make the role gate explicit at the
-- RLS layer for the three write directions, matching V14's
-- patient-write policies. The SELECT policy is unchanged —
-- identifier visibility SHOULD match patient visibility (any
-- ACTIVE member can see patient identifiers that belong to a
-- patient they can already see).
--
-- ### Why this is a V-migration (not a fix to V14)
--
-- V14 is committed and has been applied in every environment
-- running Medcore. Flyway migrations are forward-only. The
-- correct path is a new migration that ALTERs the existing
-- policies via DROP + recreate — not a retroactive edit of V14.
--
-- ### Tests updated to prove the new gate
--
-- `PatientSchemaRlsTest` gains 4 cases:
--   - OWNER can INSERT identifier (existing test, now stronger)
--   - MEMBER refused on INSERT (new — RLS WITH CHECK refusal)
--   - MEMBER refused on UPDATE (new)
--   - MEMBER refused on DELETE (new)
--
-- ### Carry-forward visibility
--
-- Revocation of an identifier in Phase 4A.3+ uses soft delete
-- via `valid_to = NOW()` — an UPDATE, not a DELETE. The DELETE
-- policy's role gate mostly guards forensic scenarios; the
-- operational write path goes through UPDATE. The UNIQUE
-- constraint
--   `uq_clinical_patient_identifier_unique
--      UNIQUE (patient_id, type, issuer, value)`
-- remains unchanged. A revoked identifier cannot be re-added
-- verbatim (same `type` + `issuer` + `value`). Tracked in the
-- 4A.3 carry-forward ledger; amend to a partial unique index
-- (WHERE valid_to IS NULL) if a pilot clinic's workflow
-- demands it.

-- ---- INSERT (OWNER/ADMIN only) ----
DROP POLICY IF EXISTS p_patient_identifier_insert ON clinical.patient_identifier;
CREATE POLICY p_patient_identifier_insert
    ON clinical.patient_identifier
    FOR INSERT
    TO medcore_app
    WITH CHECK (
        EXISTS (
            SELECT 1
              FROM clinical.patient p
              JOIN tenancy.tenant_membership tm
                ON tm.tenant_id = p.tenant_id
             WHERE p.id = clinical.patient_identifier.patient_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

-- ---- UPDATE (OWNER/ADMIN only) ----
DROP POLICY IF EXISTS p_patient_identifier_update ON clinical.patient_identifier;
CREATE POLICY p_patient_identifier_update
    ON clinical.patient_identifier
    FOR UPDATE
    TO medcore_app
    USING (
        EXISTS (
            SELECT 1
              FROM clinical.patient p
              JOIN tenancy.tenant_membership tm
                ON tm.tenant_id = p.tenant_id
             WHERE p.id = clinical.patient_identifier.patient_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1
              FROM clinical.patient p
              JOIN tenancy.tenant_membership tm
                ON tm.tenant_id = p.tenant_id
             WHERE p.id = clinical.patient_identifier.patient_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

-- ---- DELETE (OWNER/ADMIN only) ----
-- Note: 4A.3 does not expose a DELETE endpoint (revoke uses
-- UPDATE to set valid_to). This policy still hardens a future
-- forensic path.
DROP POLICY IF EXISTS p_patient_identifier_delete ON clinical.patient_identifier;
CREATE POLICY p_patient_identifier_delete
    ON clinical.patient_identifier
    FOR DELETE
    TO medcore_app
    USING (
        EXISTS (
            SELECT 1
              FROM clinical.patient p
              JOIN tenancy.tenant_membership tm
                ON tm.tenant_id = p.tenant_id
             WHERE p.id = clinical.patient_identifier.patient_id
               AND tm.user_id
                    = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

-- SELECT policy intentionally NOT changed — identifiers inherit
-- patient visibility (any ACTIVE member). The role gate is a
-- write-side concern.
