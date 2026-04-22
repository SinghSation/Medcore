-- V13__tenancy_membership_rls_admin_read.sql — Phase 3J.N
--
-- Expands the RLS read-policy surface on `tenancy.tenant_membership`
-- so an OWNER/ADMIN can SELECT every membership row in their own
-- tenant. Prerequisite for membership-management mutations in 3J.N:
-- role updates and revocations must load the target membership
-- (JPA's UPDATE flow performs a SELECT first), and the target is
-- necessarily a different user's row.
--
-- ### Why a SECURITY DEFINER helper function
--
-- The naïve approach — policy USING clause with an EXISTS
-- subquery against `tenancy.tenant_membership` — triggers
-- "infinite recursion detected in policy" because the subquery
-- re-enters the same policy. PostgreSQL aborts the query rather
-- than attempt evaluation.
--
-- The clean fix: encapsulate the "is caller an admin of this
-- tenant?" check inside a SECURITY DEFINER function whose OWNER
-- has `BYPASSRLS`. When the function runs, its internal SELECT
-- against `tenancy.tenant_membership` bypasses all RLS policies
-- (including the one calling it), so no recursion.
--
-- ### Threat model
--
-- `medcore_rls_helper` is NOLOGIN — no one can connect directly
-- as this role. Its ONLY purpose is to own RLS-policy helper
-- functions. `medcore_app` gets EXECUTE privilege on the helper
-- function but cannot invoke anything else under that role's
-- authority. The BYPASSRLS power is encapsulated in the specific
-- function body; it cannot be exercised outside that scope.
--
-- ### Scope not changed
--
--   - V8's `p_membership_select_own` remains untouched (caller
--     can still SELECT their own rows).
--   - V12 WRITE policies unchanged.
--   - `tenancy.tenant` SELECT policy unchanged.
--
-- Additive, fail-closed: PostgreSQL OR's multiple SELECT policies.
-- Non-admin callers see only their own rows (via V8). Admin
-- callers see every row in their tenant (via V13).
--
-- Rollback: DROP the V13 policy, then the function, then the role.
-- Safe at 3J.N scale because no PHI table yet depends on it. Once
-- a PHI-bearing module depends on the policy, rollback requires
-- an ADR (ADR-001 §7).

-- -----------------------------------------------------------------------
-- Helper role
-- -----------------------------------------------------------------------
-- BYPASSRLS so helper functions can query RLS-protected tables
-- from inside RLS policy USING clauses without recursion. NOLOGIN
-- so no one can connect AS this role directly. Function-execution
-- is the only pathway to exercise the role's privileges.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'medcore_rls_helper') THEN
        EXECUTE 'CREATE ROLE medcore_rls_helper WITH NOLOGIN NOINHERIT NOSUPERUSER BYPASSRLS';
    END IF;
END
$$;

-- The helper role needs concrete schema USAGE + table SELECT
-- grants — BYPASSRLS only suppresses row-level policies, not the
-- underlying privilege checks. Without these, the SECURITY
-- DEFINER function fails with "permission denied for schema
-- tenancy" when invoked.
GRANT USAGE ON SCHEMA tenancy TO medcore_rls_helper;
GRANT SELECT ON tenancy.tenant_membership TO medcore_rls_helper;

-- -----------------------------------------------------------------------
-- Helper function: is caller an ACTIVE OWNER or ADMIN of the tenant?
-- -----------------------------------------------------------------------
CREATE OR REPLACE FUNCTION tenancy.caller_is_tenant_admin(
    p_tenant_id UUID,
    p_caller_id UUID
)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = tenancy, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM tenancy.tenant_membership
         WHERE tenant_id = p_tenant_id
           AND user_id = p_caller_id
           AND status = 'ACTIVE'
           AND role IN ('OWNER', 'ADMIN')
    );
$$;

-- Transfer ownership so the function runs with BYPASSRLS.
ALTER FUNCTION tenancy.caller_is_tenant_admin(UUID, UUID) OWNER TO medcore_rls_helper;

-- Restrict invocation.
REVOKE ALL ON FUNCTION tenancy.caller_is_tenant_admin(UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.caller_is_tenant_admin(UUID, UUID) TO medcore_app;
-- medcore_migrator also receives EXECUTE so migration-time tests
-- and future admin scripts can invoke it without privilege escalation.
GRANT EXECUTE ON FUNCTION tenancy.caller_is_tenant_admin(UUID, UUID) TO medcore_migrator;

-- -----------------------------------------------------------------------
-- New SELECT policy: admin/owner sees every row in their tenant
-- -----------------------------------------------------------------------
DROP POLICY IF EXISTS p_membership_select_by_admin_or_owner
    ON tenancy.tenant_membership;

CREATE POLICY p_membership_select_by_admin_or_owner
    ON tenancy.tenant_membership
    FOR SELECT
    TO medcore_app
    USING (
        tenancy.caller_is_tenant_admin(
            tenancy.tenant_membership.tenant_id,
            NULLIF(current_setting('app.current_user_id', true), '')::uuid
        )
    );
