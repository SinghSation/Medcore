-- V6__tenant_membership.sql — Phase 3B.1
--
-- Links an `identity.user.id` to a `tenancy.tenant.id` with a role and a
-- lifecycle status. Access to any tenant-scoped surface requires an
-- ACTIVE row here; a SUSPENDED or REVOKED row (or no row at all) denies.
--
-- Cross-module reference discipline (ADR-001 §2):
--   - tenant_id FK -> tenancy.tenant(id)   -- OK, same module
--   - user_id   NO FK                      -- opaque UUID into identity.user;
--                                             NEVER a cross-module FK
--
-- The identity module enforces uniqueness / existence of the user ID inside
-- its own boundary; the tenancy module trusts the caller's authenticated
-- userId (supplied by the platform security layer) and stores it as a
-- bare UUID.
--
-- Data classification: Internal (carries an `identity.user.id`, which is
-- itself an opaque Medcore-internal identifier — NO PII, NO PHI).
--
-- Audit columns: row_version + created_at/updated_at, same policy as V4
-- and V5. `created_by`/`updated_by` deferred to the slice that lands the
-- request-scoped actor context.
--
-- Locking / size: CREATE TABLE on an empty schema; instant. No backfill.
-- Safe to re-apply: IF NOT EXISTS on the table and indexes.
--
-- Rollback: `DROP TABLE tenancy.tenant_membership` before
-- `tenancy.tenant`. No PHI is yet linked through this table.

CREATE TABLE IF NOT EXISTS tenancy.tenant_membership (
    id          UUID        NOT NULL,
    tenant_id   UUID        NOT NULL,
    user_id     UUID        NOT NULL,
    role        TEXT        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    row_version BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_tenancy_tenant_membership PRIMARY KEY (id),
    CONSTRAINT fk_tenancy_membership_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenant (id),
    CONSTRAINT uq_tenancy_membership_tenant_user
        UNIQUE (tenant_id, user_id),
    CONSTRAINT ck_tenancy_membership_role
        CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    CONSTRAINT ck_tenancy_membership_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED'))
);

-- Reverse-lookup: "who belongs to this tenant?" — covers admin/listing.
CREATE INDEX IF NOT EXISTS ix_tenancy_membership_tenant_id
    ON tenancy.tenant_membership (tenant_id);

-- Forward-lookup: "which tenants does this user belong to?" — the hot
-- path for GET /api/v1/tenants.
CREATE INDEX IF NOT EXISTS ix_tenancy_membership_user_id
    ON tenancy.tenant_membership (user_id);
