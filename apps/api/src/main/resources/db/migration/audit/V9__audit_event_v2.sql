-- V9__audit_event_v2.sql — Phase 3D (ADR-003 §2 Audit v2 hash chain)
--
-- Upgrades audit.audit_event from v1 (append-only, grant-immutable) to
-- v2 (append-only, grant-immutable, cryptographically chained). Adds
-- three columns, installs canonicalisation + chain-step + append +
-- verification functions, backfills existing rows in-place under an
-- advisory lock, then enforces NOT NULL on the new columns.
--
-- ADR-003 §2 specifics applied:
--   - sequence_no BIGINT NOT NULL (monotonic per chain)
--   - prev_hash   BYTEA (NULL only for sequence_no = 1)
--   - row_hash    BYTEA NOT NULL
--   - row_hash = SHA-256(canonical(row) || prev_hash) via the
--     `audit.compute_chain_hash` function; canonicalisation is
--     deterministic (see `audit.canonicalize_event`).
--   - Chain inserts serialised by `pg_advisory_xact_lock` keyed on a
--     global constant (single-chain design at v2; per-tenant sharding
--     can land in a later ADR if volume demands).
--   - Existing v1 rows are backfilled in this migration under the
--     same advisory lock; no v1 row is left unchained.
--
-- Verification:
--   `audit.verify_chain()` walks the chain in sequence order,
--   recomputes each row's hash, and returns the first broken row (or
--   no rows if the chain is intact). `AuditV2ChainTest` exercises it.
--
-- Writer path (handled in JdbcAuditWriter Kotlin change):
--   The application writer now calls `audit.append_event(...)` which
--   atomically acquires the advisory lock, fetches the prior
--   row_hash / sequence_no, computes the new canonical + hash, and
--   INSERTs. Application code never computes the hash itself —
--   canonicalisation lives exclusively in the DB so backfill, future
--   writes, and verification share the same source of truth.
--
-- Immutability:
--   V7's grant model is preserved. medcore_app is granted EXECUTE on
--   `audit.append_event` here so the app can insert via the function,
--   but still has no UPDATE / DELETE / TRUNCATE on audit_event.
--   AuditImmutabilityTest continues to prove the negative path.
--
-- Idempotency: `ADD COLUMN IF NOT EXISTS` is not strictly necessary
-- under Flyway's single-apply semantics, but used here defensively.
-- `CREATE OR REPLACE FUNCTION` handles function updates. The backfill
-- block is re-runnable (it sets sequence_no from 1 regardless of
-- prior state), but Flyway pins V9 to one execution.
--
-- Locking: Single ACCESS EXCLUSIVE on ADD COLUMN. The backfill
-- serialises via `pg_advisory_xact_lock` — no row-level lock
-- escalation. Safe at Phase 3D scale (audit table empty or minimal
-- in all non-prod environments).
--
-- Rollback:
--   ALTER TABLE audit.audit_event DROP COLUMN row_hash;
--   ALTER TABLE audit.audit_event DROP COLUMN prev_hash;
--   ALTER TABLE audit.audit_event DROP COLUMN sequence_no;
--   DROP FUNCTION audit.append_event, audit.verify_chain,
--       audit.rebuild_chain, audit.compute_chain_hash,
--       audit.canonicalize_event;
--   REVOKE EXECUTE ON audit.append_event FROM medcore_app;
-- Historical v1 rows remain valid after rollback (the v1 invariants
-- do not depend on the v2 columns). Cryptographic integrity is
-- forfeited until v2 re-lands.

-- -----------------------------------------------------------------------
-- 1. Schema additions
-- -----------------------------------------------------------------------
ALTER TABLE audit.audit_event ADD COLUMN IF NOT EXISTS sequence_no BIGINT;
ALTER TABLE audit.audit_event ADD COLUMN IF NOT EXISTS prev_hash   BYTEA;
ALTER TABLE audit.audit_event ADD COLUMN IF NOT EXISTS row_hash    BYTEA;

-- -----------------------------------------------------------------------
-- 2. Canonicalisation (deterministic string form of a row)
-- -----------------------------------------------------------------------
-- Single source of truth for "what is hashed". Must stay byte-stable
-- across writer path, backfill, and verification. The pipe-delimited
-- format is chosen over JSON to keep encoding variance out of the
-- hash input (no key ordering, no whitespace, no escape ambiguity).
--
-- TIMESTAMPTZ: rendered in UTC with microsecond precision via
--   to_char(... AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.USOF')
-- Nullable fields: rendered as empty strings between delimiters.
-- INET: rendered via host() to drop netmask suffix for consistency.
CREATE OR REPLACE FUNCTION audit.canonicalize_event(
    p_id            UUID,
    p_recorded_at   TIMESTAMPTZ,
    p_tenant_id     UUID,
    p_actor_type    TEXT,
    p_actor_id      UUID,
    p_actor_display TEXT,
    p_action        TEXT,
    p_resource_type TEXT,
    p_resource_id   TEXT,
    p_outcome       TEXT,
    p_request_id    TEXT,
    p_client_ip     INET,
    p_user_agent    TEXT,
    p_reason        TEXT
) RETURNS TEXT
LANGUAGE sql IMMUTABLE AS $$
    SELECT
        COALESCE(p_id::text, '')                                                           || '|' ||
        COALESCE(to_char(p_recorded_at AT TIME ZONE 'UTC',
                         'YYYY-MM-DD"T"HH24:MI:SS.USOF'), '')                              || '|' ||
        COALESCE(p_tenant_id::text, '')                                                    || '|' ||
        COALESCE(p_actor_type, '')                                                         || '|' ||
        COALESCE(p_actor_id::text, '')                                                     || '|' ||
        COALESCE(p_actor_display, '')                                                      || '|' ||
        COALESCE(p_action, '')                                                             || '|' ||
        COALESCE(p_resource_type, '')                                                      || '|' ||
        COALESCE(p_resource_id, '')                                                        || '|' ||
        COALESCE(p_outcome, '')                                                            || '|' ||
        COALESCE(p_request_id, '')                                                         || '|' ||
        COALESCE(host(p_client_ip), '')                                                    || '|' ||
        COALESCE(p_user_agent, '')                                                         || '|' ||
        COALESCE(p_reason, '')
$$;

-- -----------------------------------------------------------------------
-- 3. Chain step: row_hash = SHA-256(canonical || hex(prev_hash))
-- -----------------------------------------------------------------------
-- Using `encode(prev_hash, 'hex')` (or empty string for the first row)
-- keeps the hashed input pure TEXT so the concatenation is
-- unambiguous regardless of `bytea_output` client setting.
CREATE OR REPLACE FUNCTION audit.compute_chain_hash(
    p_canonical TEXT,
    p_prev_hash BYTEA
) RETURNS BYTEA
LANGUAGE sql IMMUTABLE AS $$
    SELECT sha256(
        convert_to(
            p_canonical || COALESCE(encode(p_prev_hash, 'hex'), ''),
            'UTF8'
        )
    )
$$;

-- -----------------------------------------------------------------------
-- 4. Rebuild: recomputes sequence_no / prev_hash / row_hash for every
--    row, from scratch, under the chain advisory lock. Used by the
--    backfill in step 7 below; also callable by ops as a recovery
--    tool (requires UPDATE on audit_event — i.e., migrator/superuser
--    only; medcore_app does NOT have UPDATE, so this function is
--    effectively unavailable at runtime and cannot be weaponised).
-- -----------------------------------------------------------------------
CREATE OR REPLACE FUNCTION audit.rebuild_chain()
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    v_prev      BYTEA  := NULL;
    v_seq       BIGINT := 0;
    r           RECORD;
    v_canonical TEXT;
    v_row_hash  BYTEA;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('audit.audit_event.chain'));
    FOR r IN
        SELECT * FROM audit.audit_event ORDER BY recorded_at, id
    LOOP
        v_seq := v_seq + 1;
        v_canonical := audit.canonicalize_event(
            r.id, r.recorded_at, r.tenant_id, r.actor_type, r.actor_id,
            r.actor_display, r.action, r.resource_type, r.resource_id,
            r.outcome, r.request_id, r.client_ip, r.user_agent, r.reason
        );
        v_row_hash := audit.compute_chain_hash(v_canonical, v_prev);
        UPDATE audit.audit_event
           SET sequence_no = v_seq,
               prev_hash   = v_prev,
               row_hash    = v_row_hash
         WHERE id = r.id;
        v_prev := v_row_hash;
    END LOOP;
    RETURN v_seq;
END;
$$;

-- -----------------------------------------------------------------------
-- 5. Append: the new write path. Called by JdbcAuditWriter in place
--    of a raw INSERT. Acquires the chain lock, derives sequence_no
--    and prev_hash from the current tip, computes row_hash, and
--    inserts. Caller-provided `id` allows the application to fix the
--    row identifier before the function runs (which makes the
--    canonical form stable even if the application generates the UUID
--    ahead of time for correlation).
-- -----------------------------------------------------------------------
CREATE OR REPLACE FUNCTION audit.append_event(
    p_id            UUID,
    p_recorded_at   TIMESTAMPTZ,
    p_tenant_id     UUID,
    p_actor_type    TEXT,
    p_actor_id      UUID,
    p_actor_display TEXT,
    p_action        TEXT,
    p_resource_type TEXT,
    p_resource_id   TEXT,
    p_outcome       TEXT,
    p_request_id    TEXT,
    p_client_ip     INET,
    p_user_agent    TEXT,
    p_reason        TEXT
) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    v_prev       BYTEA;
    v_seq        BIGINT;
    v_canonical  TEXT;
    v_row_hash   BYTEA;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('audit.audit_event.chain'));

    SELECT row_hash, sequence_no
      INTO v_prev, v_seq
      FROM audit.audit_event
     ORDER BY sequence_no DESC NULLS LAST
     LIMIT 1;

    v_seq := COALESCE(v_seq, 0) + 1;
    v_canonical := audit.canonicalize_event(
        p_id, p_recorded_at, p_tenant_id, p_actor_type, p_actor_id,
        p_actor_display, p_action, p_resource_type, p_resource_id,
        p_outcome, p_request_id, p_client_ip, p_user_agent, p_reason
    );
    v_row_hash := audit.compute_chain_hash(v_canonical, v_prev);

    INSERT INTO audit.audit_event (
        id, recorded_at, tenant_id, actor_type, actor_id, actor_display,
        action, resource_type, resource_id, outcome,
        request_id, client_ip, user_agent, reason,
        sequence_no, prev_hash, row_hash
    ) VALUES (
        p_id, p_recorded_at, p_tenant_id, p_actor_type, p_actor_id,
        p_actor_display, p_action, p_resource_type, p_resource_id,
        p_outcome, p_request_id, p_client_ip, p_user_agent, p_reason,
        v_seq, v_prev, v_row_hash
    );

    RETURN v_seq;
END;
$$;

-- -----------------------------------------------------------------------
-- 6. Verify: walks the chain in sequence order, returns the first
--    broken row (or zero rows if the chain is intact). The
--    application chain-integrity test (`AuditV2ChainTest`) calls this
--    after happy-path writes and after deliberate tampering.
-- -----------------------------------------------------------------------
CREATE OR REPLACE FUNCTION audit.verify_chain()
RETURNS TABLE(
    broken_sequence BIGINT,
    broken_row_id   UUID,
    reason          TEXT,
    expected_hash   BYTEA,
    actual_hash     BYTEA
)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    r            RECORD;
    v_prev       BYTEA  := NULL;
    v_prev_seq   BIGINT := 0;
    v_canonical  TEXT;
    v_expected   BYTEA;
BEGIN
    FOR r IN
        SELECT * FROM audit.audit_event ORDER BY sequence_no
    LOOP
        IF r.sequence_no IS NULL THEN
            broken_sequence := v_prev_seq + 1;
            broken_row_id   := r.id;
            reason          := 'sequence_no_null';
            expected_hash   := NULL;
            actual_hash     := r.row_hash;
            RETURN NEXT;
            RETURN;
        END IF;

        IF r.sequence_no <> v_prev_seq + 1 THEN
            broken_sequence := r.sequence_no;
            broken_row_id   := r.id;
            reason          := 'sequence_gap';
            expected_hash   := NULL;
            actual_hash     := r.row_hash;
            RETURN NEXT;
            RETURN;
        END IF;

        IF r.sequence_no = 1 THEN
            IF r.prev_hash IS NOT NULL THEN
                broken_sequence := r.sequence_no;
                broken_row_id   := r.id;
                reason          := 'first_row_has_prev_hash';
                expected_hash   := NULL;
                actual_hash     := r.prev_hash;
                RETURN NEXT;
                RETURN;
            END IF;
        ELSE
            IF r.prev_hash IS DISTINCT FROM v_prev THEN
                broken_sequence := r.sequence_no;
                broken_row_id   := r.id;
                reason          := 'prev_hash_mismatch';
                expected_hash   := v_prev;
                actual_hash     := r.prev_hash;
                RETURN NEXT;
                RETURN;
            END IF;
        END IF;

        v_canonical := audit.canonicalize_event(
            r.id, r.recorded_at, r.tenant_id, r.actor_type, r.actor_id,
            r.actor_display, r.action, r.resource_type, r.resource_id,
            r.outcome, r.request_id, r.client_ip, r.user_agent, r.reason
        );
        v_expected := audit.compute_chain_hash(v_canonical, v_prev);

        IF v_expected IS DISTINCT FROM r.row_hash THEN
            broken_sequence := r.sequence_no;
            broken_row_id   := r.id;
            reason          := 'row_hash_mismatch';
            expected_hash   := v_expected;
            actual_hash     := r.row_hash;
            RETURN NEXT;
            RETURN;
        END IF;

        v_prev     := r.row_hash;
        v_prev_seq := r.sequence_no;
    END LOOP;

    RETURN;
END;
$$;

-- -----------------------------------------------------------------------
-- 7. Backfill existing v1 rows in-place
-- -----------------------------------------------------------------------
-- If the table is empty (fresh cluster / test fixture), this is a
-- no-op. If the table holds v1 rows (production rollout from 3C),
-- every row gets a sequence_no, prev_hash (NULL for the first),
-- and row_hash. After this block, the table is fully v2-shaped and
-- ready for NOT NULL enforcement in step 8.
SELECT audit.rebuild_chain();

-- -----------------------------------------------------------------------
-- 8. Enforce NOT NULL on the new columns now that every existing row
--    has them populated. `prev_hash` stays nullable (first row only).
-- -----------------------------------------------------------------------
ALTER TABLE audit.audit_event ALTER COLUMN sequence_no SET NOT NULL;
ALTER TABLE audit.audit_event ALTER COLUMN row_hash    SET NOT NULL;

-- -----------------------------------------------------------------------
-- 9. Sequence uniqueness
-- -----------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS ix_audit_event_sequence_no
    ON audit.audit_event (sequence_no);

-- -----------------------------------------------------------------------
-- 10. Grants
-- -----------------------------------------------------------------------
-- medcore_app can call the append function (this is how INSERTs now
-- happen); it does NOT get EXECUTE on rebuild_chain (that requires
-- UPDATE on audit_event, which medcore_app was never granted and
-- never will be at this tier).
GRANT EXECUTE ON FUNCTION audit.append_event(
    UUID, TIMESTAMPTZ, UUID, TEXT, UUID, TEXT, TEXT, TEXT, TEXT, TEXT,
    TEXT, INET, TEXT, TEXT
) TO medcore_app;

-- Verification is a read-only function. Open it to medcore_app so an
-- in-app integrity check can be wired up in a later slice without
-- another migration.
GRANT EXECUTE ON FUNCTION audit.verify_chain() TO medcore_app;
