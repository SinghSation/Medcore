-- V5__tenant.sql — Phase 3B.1
--
-- First real tenancy DDL. A `tenant` is the top-level isolation boundary for
-- every PHI-bearing module that will land in later phases. At 3B.1 it is
-- used only to scope the authenticated caller's read surface (/tenants and
-- /tenants/{slug}/me); no PHI-bearing table yet references it.
--
-- Row-level security is NOT enabled in this migration. Tenancy enforcement
-- is service-layer only during Phases 3A–3C; RLS policies land in Phase 3D
-- as a hard gate before any Phase 4 or PHI-bearing work (ADR-001 §2).
--
-- Data classification: Internal. NO PHI, NO PII.
--
-- Audit columns: row_version for optimistic concurrency;
-- created_at/updated_at written by the application against an injected UTC
-- clock (Rule 05, Rule 06). `created_by`/`updated_by` are intentionally NOT
-- shipped yet; they arrive with the actor-context work in a later slice so
-- we don't materialise always-null columns with no writer.
--
-- Locking / size: CREATE TABLE on an empty schema; instant. No backfill.
-- Safe to re-apply: IF NOT EXISTS on the table and indexes.
--
-- Rollback: `DROP TABLE tenancy.tenant` (after dropping
-- tenancy.tenant_membership per V6's rollback header). Acceptable at this
-- phase because no PHI is yet linked to a tenant.

CREATE TABLE IF NOT EXISTS tenancy.tenant (
    id           UUID        NOT NULL,
    slug         TEXT        NOT NULL,
    display_name TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    row_version  BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_tenancy_tenant PRIMARY KEY (id),
    CONSTRAINT uq_tenancy_tenant_slug UNIQUE (slug),
    CONSTRAINT ck_tenancy_tenant_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    -- Slugs are caller-facing URL tokens; keep the shape conservative so
    -- we don't regret allowing unicode/whitespace/reserved chars later.
    CONSTRAINT ck_tenancy_tenant_slug_shape
        CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$')
);

-- Status filters are the hot path for listings; partial index covers the
-- active-tenants case without bloating the full index.
CREATE INDEX IF NOT EXISTS ix_tenancy_tenant_active
    ON tenancy.tenant (slug)
    WHERE status = 'ACTIVE';
