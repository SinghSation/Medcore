-- V12: RLS WRITE policies on tenant + tenant_membership (Phase 3J)
--
-- V8 installed RLS read policies. V12 adds the write side: even if
-- application-layer authorization is bypassed, Postgres refuses
-- cross-tenant writes. Defence in depth per ADR-007 §4.4.
--
-- Policy model (matches V8's `app.current_user_id` GUC convention):
--
--   tenancy.tenant:
--     INSERT  -- denied for medcore_app (bootstrap via SECURITY
--                DEFINER function only; see bootstrap_create_tenant).
--     UPDATE  -- caller must have an ACTIVE OWNER or ADMIN
--                membership in the target tenant.
--     DELETE  -- caller must have an ACTIVE OWNER membership.
--
--   tenancy.tenant_membership:
--     INSERT  -- target tenant's caller must have ACTIVE OWNER/ADMIN.
--     UPDATE  -- same: OWNER/ADMIN of the tenant.
--     DELETE  -- same: OWNER/ADMIN of the tenant.
--
-- Note: DELETE policies are installed even though Phase 3J does not
-- ship DELETE endpoints — defence in depth for when they do land.

-- ---- tenant writes ----

CREATE POLICY p_tenant_insert_none
    ON tenancy.tenant
    FOR INSERT
    TO medcore_app
    WITH CHECK (false);

CREATE POLICY p_tenant_update_by_admin_or_owner
    ON tenancy.tenant
    FOR UPDATE
    TO medcore_app
    USING (
        EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = tenancy.tenant.id
               AND tm.user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = tenancy.tenant.id
               AND tm.user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role IN ('OWNER', 'ADMIN')
        )
    );

CREATE POLICY p_tenant_delete_by_owner
    ON tenancy.tenant
    FOR DELETE
    TO medcore_app
    USING (
        EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm
             WHERE tm.tenant_id = tenancy.tenant.id
               AND tm.user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm.status = 'ACTIVE'
               AND tm.role = 'OWNER'
        )
    );

-- Grant the underlying DML to medcore_app (V10 granted SELECT/INSERT
-- on identity.user; tenancy.tenant grants were implicit until now).
-- RLS policies above restrict what each DML reaches; the grants are
-- the necessary prerequisite for the policies to evaluate.
GRANT SELECT, INSERT, UPDATE, DELETE ON tenancy.tenant TO medcore_app;

-- ---- membership writes ----

CREATE POLICY p_membership_insert_by_admin_or_owner
    ON tenancy.tenant_membership
    FOR INSERT
    TO medcore_app
    WITH CHECK (
        EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm_admin
             WHERE tm_admin.tenant_id = tenancy.tenant_membership.tenant_id
               AND tm_admin.user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm_admin.status = 'ACTIVE'
               AND tm_admin.role IN ('OWNER', 'ADMIN')
        )
    );

CREATE POLICY p_membership_update_by_admin_or_owner
    ON tenancy.tenant_membership
    FOR UPDATE
    TO medcore_app
    USING (
        EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm_admin
             WHERE tm_admin.tenant_id = tenancy.tenant_membership.tenant_id
               AND tm_admin.user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm_admin.status = 'ACTIVE'
               AND tm_admin.role IN ('OWNER', 'ADMIN')
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm_admin
             WHERE tm_admin.tenant_id = tenancy.tenant_membership.tenant_id
               AND tm_admin.user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm_admin.status = 'ACTIVE'
               AND tm_admin.role IN ('OWNER', 'ADMIN')
        )
    );

CREATE POLICY p_membership_delete_by_admin_or_owner
    ON tenancy.tenant_membership
    FOR DELETE
    TO medcore_app
    USING (
        EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership tm_admin
             WHERE tm_admin.tenant_id = tenancy.tenant_membership.tenant_id
               AND tm_admin.user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND tm_admin.status = 'ACTIVE'
               AND tm_admin.role IN ('OWNER', 'ADMIN')
        )
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON tenancy.tenant_membership TO medcore_app;

-- ---- Bootstrap tenant-creation helper (ADR-007 §4.5) ----
--
-- Phase 3J does not ship a runtime caller for this function. It
-- exists so a future admin / IdP bootstrap slice (Phase 3K or
-- dedicated) has a clean DB-level path that bypasses the
-- "must be a member to create" chicken-and-egg.
--
-- SECURITY DEFINER runs as the function's owner (medcore_migrator),
-- which has CREATE privileges on the schema. EXECUTE is granted
-- ONLY to medcore_migrator — no app-role caller can invoke this
-- even by accident.

CREATE OR REPLACE FUNCTION tenancy.bootstrap_create_tenant(
    p_slug            TEXT,
    p_display_name    TEXT,
    p_owner_user_id   UUID
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = tenancy, pg_temp
AS $$
DECLARE
    v_tenant_id UUID;
    v_membership_id UUID;
BEGIN
    INSERT INTO tenancy.tenant (id, slug, display_name, status, created_at, updated_at, row_version)
         VALUES (gen_random_uuid(), p_slug, p_display_name, 'ACTIVE', NOW(), NOW(), 0)
      RETURNING id INTO v_tenant_id;

    INSERT INTO tenancy.tenant_membership (
        id, tenant_id, user_id, role, status, created_at, updated_at, row_version
    )
    VALUES (
        gen_random_uuid(), v_tenant_id, p_owner_user_id, 'OWNER', 'ACTIVE', NOW(), NOW(), 0
    )
    RETURNING id INTO v_membership_id;

    RETURN v_tenant_id;
END;
$$;

-- EXECUTE grant: ONLY the migrator role. Any other role (medcore_app,
-- future service accounts) calling this function fails with
-- "permission denied for function bootstrap_create_tenant".
REVOKE ALL ON FUNCTION tenancy.bootstrap_create_tenant(TEXT, TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.bootstrap_create_tenant(TEXT, TEXT, UUID) TO medcore_migrator;
