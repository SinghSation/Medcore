-- V8__tenancy_rls.sql — Phase 3D (ADR-001 §2 RLS substrate)
--
-- Scope of this migration: the RLS substrate. Policies, GUC contract,
-- grants, and direct test coverage against the non-superuser role.
--
-- Scope NOT included (deliberate, explicit re-scope): runtime
-- enforcement for the running application. The local-dev datasource
-- still connects as the container superuser, which BYPASSES RLS by
-- Postgres design (FORCE ROW LEVEL SECURITY does not bind
-- superusers). RLS correctness is proven against `medcore_app`, but
-- runtime enforcement for the running application remains dependent
-- on the deferred datasource-role switch (separate Tier 3 ops slice;
-- prerequisite for Phase 4 / any PHI-bearing module). When that
-- slice lands, no migration change here is required — the policies
-- below already cover `medcore_app`.
--
-- Enables PostgreSQL Row-Level Security on the existing tenant-scoped
-- tables and installs policies keyed on the session GUCs
-- `app.current_user_id` and `app.current_tenant_id`. Service-layer
-- checks in `TenancyService` remain in place; RLS is the DB-layer
-- backstop once the datasource-role switch lands (ADR-001 §2:
-- "Service-layer isolation is the temporary enforcement mechanism
-- for Phases 3A–3C. Database-enforced RLS is mandatory before any
-- Phase 4 or PHI-bearing work begins.").
--
-- Scope (exhaustive — per the Phase 3D prompt "no partial RLS" rule):
--   - tenancy.tenant              — ENABLE RLS + policy
--   - tenancy.tenant_membership   — ENABLE RLS + policy
--
-- `identity.user` and `audit.audit_event` are intentionally NOT in
-- scope: identity.user is a cross-tenant infrastructure table by
-- ADR-001 §2; audit.audit_event can carry a nullable tenant_id but is
-- not itself a tenant-owned business table and is governed by the
-- append-only grant model (V7) + hash chain (V9).
--
-- GUC semantics:
--   - `app.current_user_id`  = identity.user.id of the authenticated
--     caller; required for ALL policies below.
--   - `app.current_tenant_id` = currently resolved tenant context
--     (TenantContextFilter); reserved here for Phase 4 PHI policies
--     but not read by any 3D policy (tenancy tables scope by user id).
--   - Read via `NULLIF(current_setting(..., true), '')::uuid`.
--     Missing/empty → NULL → comparison fails → zero rows returned
--     (FAIL-CLOSED, per the slice requirement).
--
-- Runtime app role:
--   This migration grants SELECT on tenancy tables to `medcore_app`
--   (the role V7 created for audit immutability) so tests can connect
--   as a non-superuser and exercise RLS end-to-end. The running
--   application still connects as the container superuser in local
--   dev. Superusers bypass RLS by Postgres design. The policies
--   below become the runtime enforcement boundary only after a
--   separate ops slice flips the application's datasource to
--   `medcore_app` (datasource-split + secret-manager wiring).
--   `TenancyRlsTest` proves the policies work by connecting AS
--   `medcore_app` directly with a local-only test password.
--
-- Idempotency: ALTER TABLE … ENABLE ROW LEVEL SECURITY is a no-op if
-- already enabled. CREATE POLICY is not IF NOT EXISTS friendly in
-- older Postgres versions; guarded here by DROP POLICY IF EXISTS so
-- the migration is re-runnable.
--
-- Locking: ALTER TABLE ENABLE ROW LEVEL SECURITY takes an ACCESS
-- EXCLUSIVE lock briefly. Safe at 3D scale (tables empty or near
-- empty in all non-prod environments). CREATE POLICY is metadata-only.
--
-- Rollback:
--   ALTER TABLE tenancy.tenant DISABLE ROW LEVEL SECURITY;
--   ALTER TABLE tenancy.tenant_membership DISABLE ROW LEVEL SECURITY;
--   DROP POLICY IF EXISTS p_tenant_select_by_active_membership ON tenancy.tenant;
--   DROP POLICY IF EXISTS p_membership_select_own ON tenancy.tenant_membership;
-- Acceptable at Phase 3D because no PHI table yet depends on RLS;
-- PROHIBITED once any PHI-bearing table is created (ADR-001 §7).

-- -----------------------------------------------------------------------
-- Grants for the application role
-- -----------------------------------------------------------------------
GRANT USAGE ON SCHEMA tenancy TO medcore_app;
GRANT SELECT ON tenancy.tenant            TO medcore_app;
GRANT SELECT ON tenancy.tenant_membership TO medcore_app;

-- -----------------------------------------------------------------------
-- Enable RLS
-- -----------------------------------------------------------------------
ALTER TABLE tenancy.tenant            ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.tenant_membership ENABLE ROW LEVEL SECURITY;

-- FORCE so the table owner (migrator) also respects RLS. Postgres
-- superusers still bypass (by design), which is documented in the
-- comment block above and tested against `medcore_app`.
ALTER TABLE tenancy.tenant            FORCE ROW LEVEL SECURITY;
ALTER TABLE tenancy.tenant_membership FORCE ROW LEVEL SECURITY;

-- -----------------------------------------------------------------------
-- Policy: tenancy.tenant
-- -----------------------------------------------------------------------
-- A caller may see a tenant row only if they hold an ACTIVE membership
-- in that tenant. SUSPENDED / REVOKED memberships do NOT reveal the
-- tenant row; neither does simple existence of the tenant.
DROP POLICY IF EXISTS p_tenant_select_by_active_membership ON tenancy.tenant;
CREATE POLICY p_tenant_select_by_active_membership
    ON tenancy.tenant
    FOR SELECT
    TO medcore_app
    USING (
        EXISTS (
            SELECT 1
              FROM tenancy.tenant_membership m
             WHERE m.tenant_id = tenancy.tenant.id
               AND m.user_id   = NULLIF(current_setting('app.current_user_id', true), '')::uuid
               AND m.status    = 'ACTIVE'
        )
    );

-- -----------------------------------------------------------------------
-- Policy: tenancy.tenant_membership
-- -----------------------------------------------------------------------
-- A caller may see ONLY their own membership rows. This covers:
--   - GET /api/v1/tenants (list of my memberships)
--   - GET /api/v1/tenants/{slug}/me (a single membership of mine)
--   - TenantContextFilter's membership lookup during X-Medcore-Tenant
--     resolution.
-- Admin surfaces (listing all memberships in a tenant) do not exist
-- yet; when they land they will use a separate policy scoped via
-- `app.current_tenant_id` + an elevated role, not this one.
DROP POLICY IF EXISTS p_membership_select_own ON tenancy.tenant_membership;
CREATE POLICY p_membership_select_own
    ON tenancy.tenant_membership
    FOR SELECT
    TO medcore_app
    USING (
        user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
    );

-- No INSERT / UPDATE / DELETE policies are defined for medcore_app on
-- either table. Default deny applies. Phase 3D intentionally does NOT
-- grant tenancy writes to the runtime role — tenancy provisioning is
-- an admin surface that arrives in a later slice.
