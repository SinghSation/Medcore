-- V7__audit_event.sql — Phase 3C (Audit v1, ADR-003 §2)
--
-- Creates the append-only `audit.audit_event` table and the DB-level
-- immutability model. This is Audit v1 only; cryptographic chaining
-- (sequence_no / prev_hash / row_hash) is Phase 3D (Audit v2, ADR-003 §2).
--
-- Column set is exactly the v1 set enumerated in ADR-003 §2. No free-form
-- JSONB bag. No PHI. Enum-like columns are TEXT + CHECK so future additive
-- values land as additive migrations.
--
-- Role / grant model (ADR-003 §2):
--   - DDL and schema ownership stay with the migrator (here: the
--     container superuser locally; `medcore_migrator` in a hardened
--     deployment).
--   - The application role `medcore_app` is granted INSERT, SELECT only.
--     UPDATE, DELETE, TRUNCATE are explicitly revoked. Tamper-evidence
--     at v1 rests on this grant alone (cryptographic integrity follows
--     in v2).
--
-- Idempotency / safety:
--
--   - `medcore_app` is created here inside a `DO` block guarded by a
--     `pg_catalog.pg_roles` existence check. The migration is therefore
--     safe to apply against a fresh database (role created) AND against
--     a database where ops has already pre-provisioned the role
--     (CREATE is skipped; the GRANT/REVOKE statements still run and
--     converge to the ADR-003 declared grant set).
--   - The migration NEVER drops or alters an existing role's password.
--     Production password is set by ops out-of-band from a secret
--     manager. Local/test password is set by the test harness inside
--     the ephemeral Testcontainers Postgres only (see
--     `AuditImmutabilityTest`). No password lives in this file or any
--     committed config.
--
-- Runtime app role:
--
--   This migration creates the role and locks down audit DML, but Phase
--   3C does NOT flip the running application's datasource to
--   `medcore_app` — that is an explicit, scoped deferral. The current
--   local app continues to connect as the container superuser; the
--   ADR-003 §2 invariant ("app role cannot UPDATE/DELETE audit") is
--   structurally enforced by the grants here and exercised by
--   `AuditImmutabilityTest`, which connects AS `medcore_app`. Promoting
--   the running datasource to `medcore_app` requires datasource-split
--   plumbing (separate Flyway datasource for DDL) and secret-manager
--   wiring; tracked as a follow-up ops slice. See
--   `docs/runbooks/local-services.md` §11.
--
-- Locking / size: CREATE TABLE on an empty schema; instant. No backfill.
-- Safe to re-apply: IF NOT EXISTS on the table and indexes.
--
-- Rollback: DROP TABLE audit.audit_event; DROP ROLE medcore_app;
-- Acceptable at this phase because no PHI has yet been audited and no
-- downstream system depends on the audit stream yet.

-- -----------------------------------------------------------------------
-- Application role
-- -----------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'medcore_app') THEN
        -- LOGIN + NOINHERIT; no password here — ops/tests set it out-of-band.
        CREATE ROLE medcore_app WITH LOGIN NOINHERIT;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA audit TO medcore_app;

-- -----------------------------------------------------------------------
-- Table: audit.audit_event (Audit v1)
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit.audit_event (
    id             UUID        NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    tenant_id      UUID,
    actor_type     TEXT        NOT NULL,
    actor_id       UUID,
    actor_display  TEXT,
    action         TEXT        NOT NULL,
    resource_type  TEXT,
    resource_id    TEXT,
    outcome        TEXT        NOT NULL,
    request_id     TEXT,
    client_ip      INET,
    user_agent     TEXT,
    reason         TEXT,

    CONSTRAINT pk_audit_event PRIMARY KEY (id),

    -- Enumerations declared at schema level so a rogue INSERT can't bypass
    -- the actor/outcome taxonomy. Add new values via additive migration
    -- only (Rule 03).
    CONSTRAINT ck_audit_event_actor_type
        CHECK (actor_type IN ('USER', 'SYSTEM', 'SERVICE')),
    CONSTRAINT ck_audit_event_outcome
        CHECK (outcome IN ('SUCCESS', 'DENIED', 'ERROR'))
);

-- BRIN on recorded_at — hot-path insert-time cost is near-zero, and the
-- table grows monotonically by timestamp so BRIN's range semantics are a
-- strong fit at scale (ADR-003 §2).
CREATE INDEX IF NOT EXISTS ix_audit_event_recorded_at_brin
    ON audit.audit_event USING BRIN (recorded_at);

-- B-tree on (tenant_id, recorded_at) — covers per-tenant timeline reads
-- for future investigations; tenant_id is nullable for cross-tenant
-- admin events (ADR-001 §2, ADR-003 §2).
CREATE INDEX IF NOT EXISTS ix_audit_event_tenant_recorded
    ON audit.audit_event (tenant_id, recorded_at);

-- -----------------------------------------------------------------------
-- Immutability grants (ADR-003 §2)
-- -----------------------------------------------------------------------
GRANT INSERT, SELECT ON audit.audit_event TO medcore_app;
-- Defensive REVOKE even though the grants above never conferred these —
-- makes intent auditable and survives any future grant drift.
REVOKE UPDATE, DELETE, TRUNCATE ON audit.audit_event FROM medcore_app;
