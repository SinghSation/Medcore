-- V4__identity_user.sql — Phase 3A.3 (ADR-002 §7 step 5)
--
-- Creates the canonical internal user table for Medcore. Exactly one row per
-- external identity `(issuer, subject)` pair; JIT-provisioned on first
-- successful token validation (ADR-002 §2).
--
-- Non-tenant table (ADR-001 §2): `identity.user` is cross-tenant infrastructure
-- and does NOT carry `tenant_id`. Per-tenant membership lives in the tenancy
-- module and references this table's `id` by opaque UUID, NOT via a
-- cross-module foreign key (ADR-001 §2).
--
-- Data classification: PII (email, preferred_username, display_name). NO PHI.
--
-- Audit columns: row_version for optimistic concurrency; created_at/updated_at
-- always populated by application code against an injected UTC clock
-- (Rule 05, Rule 06). `created_by` / `updated_by` columns are reserved for
-- the next slice where a request-scoped actor context exists — omitted here
-- to avoid shipping an always-null column with no writer.
--
-- Locking / size: CREATE TABLE on an empty schema; instant. No backfill.
-- Safe to re-apply: IF NOT EXISTS on the table and indexes.
--
-- Rollback: `DROP TABLE identity.user;` is acceptable at this phase because no
-- PHI has been written and identity has no downstream production consumers
-- yet (ADR-001 §7 Rollback plan, Phase 3A).

CREATE TABLE IF NOT EXISTS identity."user" (
    id                 UUID        NOT NULL,
    issuer             TEXT        NOT NULL,
    subject            TEXT        NOT NULL,
    email              TEXT,
    email_verified     BOOLEAN     NOT NULL DEFAULT FALSE,
    display_name       TEXT,
    preferred_username TEXT,
    status             TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    row_version        BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_identity_user PRIMARY KEY (id),
    CONSTRAINT uq_identity_user_issuer_subject UNIQUE (issuer, subject),
    CONSTRAINT ck_identity_user_status
        CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED'))
);

-- Indexed for operator lookup by email. Partial index keeps non-null-only
-- rows and avoids a uniqueness commitment we cannot honor across providers.
CREATE INDEX IF NOT EXISTS ix_identity_user_email
    ON identity."user" (email)
    WHERE email IS NOT NULL;
