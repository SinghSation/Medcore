-- V11: Provision the medcore_migrator role for out-of-process Flyway
-- execution and grant medcore_app the flyway_schema_history visibility
-- FlywayMigrationStateCheck needs.
--
-- Phase 3H — Secrets + production posture.
-- ADR-006 — Production secrets + migration architecture.
--
-- Intent of this migration:
--
--   1. Create `medcore_migrator` idempotently. Grants are LEAST-
--      PRIVILEGE: schema USAGE + CREATE (needed for DDL), ALL on
--      the flyway schema's tables (owns flyway_schema_history
--      going forward), and an explicit REVOKE on application-
--      schema table reads (the migrator does DDL, not data
--      access).
--
--   2. Explicit role attributes: LOGIN (the CLI container / Gradle
--      plugin log in), NOINHERIT (no implicit role memberships),
--      NOBYPASSRLS (RLS policies still apply to the migrator —
--      defence-in-depth against a future migration accidentally
--      selecting from a tenant-scoped table), NOCREATEDB,
--      NOCREATEROLE, NOSUPERUSER, NOREPLICATION.
--
--   3. Grant medcore_app the MINIMUM visibility needed for
--      FlywayMigrationStateCheck to query flyway_schema_history:
--      USAGE on schema `flyway`, SELECT on flyway_schema_history.
--      No other flyway-schema grants — the app MUST NOT write to
--      the migration history.
--
-- This migration does NOT:
--   - Set the migrator's password (ops concern; ADR-006 §6).
--   - Grant application-data read access to the migrator.
--   - Grant the migrator ownership over existing application
--     tables (those remain migrator-owned by creation history
--     and/or explicit ownership transfers in future slices if
--     needed).
--
-- Re-runnability: DO blocks with existence checks make this
-- migration safe to run on a fresh cluster OR on an existing dev
-- cluster where earlier V*.sql migrations ran as the container
-- superuser. Production deployments run V11 via the Flyway CLI
-- container (not in-app), as the migrator itself.

-- ---------------------------------------------------------------
-- 1. Create medcore_migrator role (idempotent)
-- ---------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'medcore_migrator') THEN
        CREATE ROLE medcore_migrator WITH
            LOGIN
            NOINHERIT
            NOBYPASSRLS
            NOCREATEDB
            NOCREATEROLE
            NOSUPERUSER
            NOREPLICATION;
    END IF;
END
$$;

-- ---------------------------------------------------------------
-- 2. medcore_migrator grants — least privilege
-- ---------------------------------------------------------------

-- Schema-level: USAGE (to resolve schema-qualified names) + CREATE
-- (to land DDL).
GRANT USAGE, CREATE ON SCHEMA flyway   TO medcore_migrator;
GRANT USAGE, CREATE ON SCHEMA identity TO medcore_migrator;
GRANT USAGE, CREATE ON SCHEMA tenancy  TO medcore_migrator;
GRANT USAGE, CREATE ON SCHEMA audit    TO medcore_migrator;

-- flyway_schema_history: the migrator is expected to own this table
-- going forward. In practice the table already exists (Flyway created
-- it on the first in-app migration run), so we GRANT explicitly.
GRANT ALL ON ALL TABLES IN SCHEMA flyway TO medcore_migrator;
GRANT ALL ON ALL SEQUENCES IN SCHEMA flyway TO medcore_migrator;

-- Application-schema tables: migrator does DDL only, not data reads.
-- Revoke broadly; future migrations that need to read existing data
-- (rare; only during non-trivial backfills) must GRANT explicitly
-- with a comment justifying the need.
REVOKE ALL ON ALL TABLES IN SCHEMA identity FROM medcore_migrator;
REVOKE ALL ON ALL TABLES IN SCHEMA tenancy  FROM medcore_migrator;
REVOKE ALL ON ALL TABLES IN SCHEMA audit    FROM medcore_migrator;

-- Future-looking: ensure tables created by medcore_migrator are
-- owned by medcore_migrator (for later ALTER TABLE in migrations)
-- without leaking any default table-level read grants to other
-- roles.
ALTER DEFAULT PRIVILEGES FOR ROLE medcore_migrator IN SCHEMA identity
    REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE medcore_migrator IN SCHEMA tenancy
    REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE medcore_migrator IN SCHEMA audit
    REVOKE ALL ON TABLES FROM PUBLIC;

-- ---------------------------------------------------------------
-- 3. medcore_app grants — minimum for FlywayMigrationStateCheck
-- ---------------------------------------------------------------

-- FlywayMigrationStateCheck queries flyway.flyway_schema_history at
-- app startup (Phase 3H) to verify migrations have run before
-- Hibernate's ddl-auto=validate kicks in. That check runs as the
-- application's runtime role (`medcore_app`), so `medcore_app` needs:
--   - USAGE on the flyway schema (to resolve schema-qualified name)
--   - SELECT on flyway_schema_history (to read `installed_rank`)
--
-- Intentionally NOT granted: INSERT/UPDATE/DELETE/TRUNCATE. The app
-- MUST NEVER write to the migration history.

GRANT USAGE ON SCHEMA flyway TO medcore_app;
GRANT SELECT ON flyway.flyway_schema_history TO medcore_app;
