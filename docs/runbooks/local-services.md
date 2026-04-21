# Runbook — Local Services

**Scope:** Starting, stopping, inspecting, and resetting the backing
services Medcore needs to run locally. macOS developer workstations.
**Audience:** Any human or AI agent that runs the Medcore backend
locally.
**Status:** Authoritative.

Medcore's `docker-compose.yml` is for developer workstations only. It is
not production configuration — production infrastructure lives in
`infra/terraform/` (ADR-001 §6).

Services defined as of Phase 3A.3:

- `postgres` — primary relational store (ADR-001).
- `mock-oauth2-server` — local OIDC IdP (ADR-002). Dev-only; the backend
  refuses to use it under the `prod` profile (ADR-002 §2).

---

## 1. Prerequisites

Install once per machine:

- **Docker Desktop for Mac** ≥ 4.30, or **Colima** ≥ 0.7 plus Docker
  CLI. Either provides `docker` and the `docker compose` plugin.
- Verify: `docker compose version` MUST report v2.30 or newer.

Docker MUST be running before any command in this runbook.

## 2. One-time setup

### 2.1 Create a local `.env`

Create `.env` at the repository root with at least the required
variable. **This file is gitignored — never commit it.**

```
# .env  — local-only, never committed
POSTGRES_PASSWORD=<choose-a-local-password>

# Optional overrides (defaults shown)
# POSTGRES_USER=medcore
# POSTGRES_DB=medcore_dev
# POSTGRES_PORT=5432
# MOCK_OAUTH2_PORT=8888
```

`POSTGRES_PASSWORD` is required. If it is unset, `docker compose up`
refuses to start — there is no default password by design.

### 2.2 Verify the `.env` is ignored

```bash
git check-ignore -v .env
# expected: .gitignore:<line>:.env    .env
```

If the command prints nothing, stop: your `.env` is tracked and must
be removed from the index before you write a real password into it.

## 3. Start

```bash
docker compose up -d postgres mock-oauth2-server
```

- Pulls `postgres:16.4` and `ghcr.io/navikt/mock-oauth2-server:2.1.10` on
  first run.
- Starts both containers detached.
- Creates the `medcore_postgres_data` volume if it doesn't exist.

Verify:

```bash
docker compose ps
```

The `postgres` service MUST report `running` with `Health: healthy`.
The `mock-oauth2-server` service MUST report `running`.
If postgres stays `starting` past ~60s, see §9.

Verify the mock OIDC discovery document is reachable:

```bash
curl -fsS http://localhost:${MOCK_OAUTH2_PORT:-8888}/default/.well-known/openid-configuration \
  | head -c 80
# expected: JSON starting with `{"issuer":"http://localhost:8888/default"...`
```

The backend's default `MEDCORE_OIDC_ISSUER_URI` points at
`http://localhost:8888/default`. Override via environment variable when
running against a different IdP.

## 4. Stop

```bash
docker compose stop postgres   # stop but keep the container
docker compose down            # stop and remove container; data volume persists
```

Neither command deletes the `postgres_data` volume. See §7 for a full
wipe.

## 5. Restart

```bash
docker compose restart postgres
```

## 6. Logs

```bash
docker compose logs -f postgres            # tail
docker compose logs --since=10m postgres   # last ten minutes
docker compose logs --tail=200 postgres    # last 200 lines
```

Postgres logs include startup banners, slow queries, and every
connection. No PHI exists in the cluster at Phase 3A; remain vigilant
once PHI-bearing modules land.

## 7. Wipe / reset

**DESTROYS ALL LOCAL DATABASE CONTENT. There is no undo.**

```bash
docker compose down --volumes
docker volume ls | grep medcore_postgres_data || echo "volume removed"
```

Recreate:

```bash
docker compose up -d postgres
```

The cluster re-initialises with the current `.env` credentials.

## 8. Connecting with `psql`

### 8.1 Inside the container (preferred for dev)

```bash
docker compose exec postgres \
  psql -U "${POSTGRES_USER:-medcore}" -d "${POSTGRES_DB:-medcore_dev}"
```

Password is not needed — `docker exec` inherits the container
environment.

### 8.2 From the host

Requires a host `psql` client (`brew install libpq` and
`brew link --force libpq`, or `brew install postgresql@16`).

```bash
# One-off, password via env var (avoids shell history):
PGPASSWORD="$(grep '^POSTGRES_PASSWORD=' .env | cut -d= -f2-)" \
  psql -h localhost -p "${POSTGRES_PORT:-5432}" \
       -U "${POSTGRES_USER:-medcore}" \
       -d "${POSTGRES_DB:-medcore_dev}"
```

Never put a password on the command line in front of another user.
Prefer `PGPASSWORD` or a `~/.pgpass` file (`man psql`).

## 9. Health verification

Shell-only health check:

```bash
docker compose exec postgres \
  pg_isready -U "${POSTGRES_USER:-medcore}" -d "${POSTGRES_DB:-medcore_dev}"
# expected: <socket>:<port> - accepting connections
```

Cluster state (once connected via §8):

```sql
SELECT version();                       -- PostgreSQL 16.x
SELECT current_database(), current_user;
SHOW timezone;                          -- UTC
```

## 10. Troubleshooting

- **`POSTGRES_PASSWORD must be set in .env or the environment`** —
  §2.1. Create `.env` with a non-empty `POSTGRES_PASSWORD=...`.
- **Container stuck in `Health: starting`** — check
  `docker compose logs postgres` for init errors. A wipe (§7) usually
  clears corrupted init state.
- **`port 5432 already in use`** — another Postgres is running on the
  host (brew service, another project, etc.). Either stop it or set
  `POSTGRES_PORT=5433` in `.env` and re-run.
- **`container name "medcore-postgres" is already in use`** — a stale
  container from a previous run exists. `docker rm -f medcore-postgres`
  and retry.
- **`no space left on device`** — Docker Desktop's virtual disk is
  full. Free space in Docker Desktop settings or run
  `docker volume prune` (this deletes unused volumes).
- **Password rotation** — changing `POSTGRES_PASSWORD` in `.env` does
  NOT change the password inside an existing cluster. Either wipe
  (§7) and recreate, or run `ALTER USER ... WITH PASSWORD '...'`
  manually inside `psql`.

## 11. Audit role (Phase 3C)

The V7 migration creates a `medcore_app` Postgres role and applies the
ADR-003 §2 immutability grants to `audit.audit_event`:

- `GRANT INSERT, SELECT ON audit.audit_event TO medcore_app`
- `REVOKE UPDATE, DELETE, TRUNCATE ON audit.audit_event FROM medcore_app`

The role is created **without a password** so the committed migration
carries no secret. In production, ops sets the role's password from the
secret manager and configures the application's datasource to connect
as `medcore_app`.

### Phase 3C scope — explicit deferral of the runtime app-role switch

Phase 3C **does NOT** flip the running application's datasource to
`medcore_app`. Locally and in tests the app process continues to
connect as the container superuser (`medcore`). This is a deliberate,
bounded scope decision, NOT an oversight. The reasoning:

- The ADR-003 §2 invariant — *the app role cannot UPDATE/DELETE audit
  rows* — is structurally enforced by the V7 grants and is exercised
  by `AuditImmutabilityTest`, which connects directly AS `medcore_app`
  and asserts that every forbidden operation fails at the DB layer.
  The grant model is real, applied, and tested.
- Switching the running datasource to `medcore_app` requires:
  splitting the Flyway datasource (which needs DDL privileges) from
  the application JPA datasource (which needs only DML on identity /
  tenancy and INSERT/SELECT on audit), wiring a secret-manager-backed
  password, and granting `medcore_app` the appropriate DML rights on
  every existing application schema (`identity.user`,
  `tenancy.tenant`, `tenancy.tenant_membership`).
- That work belongs to a dedicated ops slice, not to the audit
  pipeline implementation. Bundling it here would either pull a real
  secret-manager dependency into Phase 3C or commit a "local password"
  string to the repo — both of which are worse than the explicit
  deferral.

Tracked carry-forward for the next operational hardening slice (and
for the eventual Phase 3D RLS work, which assumes the runtime role
split is in place).

To exercise the role manually in your local cluster:

```bash
docker compose exec postgres \
  psql -U "${POSTGRES_USER:-medcore}" -d "${POSTGRES_DB:-medcore_dev}" \
  -c "ALTER ROLE medcore_app WITH PASSWORD 'local-only-do-not-reuse';"

PGPASSWORD='local-only-do-not-reuse' psql \
  -h localhost -p "${POSTGRES_PORT:-5432}" \
  -U medcore_app -d "${POSTGRES_DB:-medcore_dev}" \
  -c "SELECT count(*) FROM audit.audit_event;"
# expected: works
PGPASSWORD='local-only-do-not-reuse' psql \
  -h localhost -p "${POSTGRES_PORT:-5432}" \
  -U medcore_app -d "${POSTGRES_DB:-medcore_dev}" \
  -c "DELETE FROM audit.audit_event;"
# expected: ERROR: permission denied for table audit_event
```

Switching the running app to connect as `medcore_app` is an ops task
tracked for a later slice (it requires datasource property plumbing and
a secret-manager wiring that lives outside this phase).

## 12. Row-level security (Phase 3D, V8)

> **Scope note.** Phase 3D lands the RLS substrate (policies, GUC
> contract, grants, tests). RLS correctness is proven against
> `medcore_app`, but **runtime enforcement for the running
> application remains dependent on the deferred datasource-role
> switch.** Until that ops slice lands, the live app connects as
> the container superuser and BYPASSES RLS by Postgres design. This
> is intentional and explicit, not an oversight.

V8 enables Postgres RLS on `tenancy.tenant` and
`tenancy.tenant_membership`. Two policies apply to `medcore_app` (the
non-superuser role V7 created):

- `p_tenant_select_by_active_membership` on `tenancy.tenant` —
  caller may see a tenant row only if they have an ACTIVE membership
  for it.
- `p_membership_select_own` on `tenancy.tenant_membership` — caller
  may see only their own rows.

Policies key on two session GUCs:

- `app.current_user_id` — required for the policies above.
- `app.current_tenant_id` — set by `TenancySessionContext` for
  consistency with Phase 4 PHI policies; not read by any 3D policy.

Both GUCs are set with `SELECT set_config(name, value, true)` (i.e.
`SET LOCAL`), so Postgres auto-reverts them at transaction
commit/rollback — no GUC leakage across pooled connections.

Superusers (the container default `medcore` user, which the app
currently connects as) BYPASS RLS. The policies activate in
production when the runtime datasource switches to `medcore_app`
(deferred ops slice — see §11 above). `TenancyRlsTest` connects
directly as `medcore_app` with the test-only password and asserts
RLS enforcement end-to-end.

To explore locally:

```bash
docker compose exec postgres \
  psql -U "${POSTGRES_USER:-medcore}" -d "${POSTGRES_DB:-medcore_dev}" \
  -c "ALTER ROLE medcore_app WITH PASSWORD 'local-only-do-not-reuse';"

PGPASSWORD='local-only-do-not-reuse' psql \
  -h localhost -p "${POSTGRES_PORT:-5432}" \
  -U medcore_app -d "${POSTGRES_DB:-medcore_dev}" <<'SQL'
BEGIN;
-- Pretend to be some identity.user.id
SELECT set_config('app.current_user_id', 'your-user-uuid', true);
SELECT slug FROM tenancy.tenant;           -- RLS-filtered
SELECT user_id FROM tenancy.tenant_membership;
ROLLBACK;
SQL
```

## 13. Audit v2 chain verification (Phase 3D, V9)

V9 adds `sequence_no`, `prev_hash`, `row_hash` to
`audit.audit_event` and four functions under the `audit` schema:

- `audit.canonicalize_event(...)` — deterministic string form of a
  row (pipe-delimited, UTC microsecond timestamps, hex-encoded
  previous hash for byte stability).
- `audit.compute_chain_hash(canonical, prev_hash)` —
  `sha256(canonical || hex(prev_hash))`.
- `audit.append_event(...)` — the new write path (replaces raw
  INSERT). Acquires `pg_advisory_xact_lock`, derives the next
  sequence number from the chain tip, computes the row hash, inserts.
  `medcore_app` has `EXECUTE`; `JdbcAuditWriter` calls it.
- `audit.verify_chain()` — returns one row per broken link with a
  coded `reason` (`sequence_gap`, `row_hash_mismatch`,
  `prev_hash_mismatch`, `first_row_has_prev_hash`, `sequence_no_null`).
  Returns zero rows for a healthy chain.

Verify a local chain:

```bash
docker compose exec postgres \
  psql -U "${POSTGRES_USER:-medcore}" -d "${POSTGRES_DB:-medcore_dev}" \
  -c "SELECT * FROM audit.verify_chain();"
# expected: 0 rows
```

Rebuild (ops-only, requires superuser UPDATE on audit.audit_event):

```bash
docker compose exec postgres \
  psql -U "${POSTGRES_USER:-medcore}" -d "${POSTGRES_DB:-medcore_dev}" \
  -c "SELECT audit.rebuild_chain();"
# returns the number of rows re-hashed
```

The `AuditV2ChainTest` integration test exercises the full loop:
happy-path appends → `verify_chain()` clean → tamper via superuser
→ `verify_chain()` reports the break with the correct reason code.

## 14. Related

- **ADR-001** — PostgreSQL + Flyway + row-level tenancy.
- `.cursor/rules/03-db-migrations.mdc` — migration rules (applies
  once Flyway lands in Phase 3A Step 2).
- `docs/runbooks/development.md` — broader local dev setup.
- `docs/runbooks/github-auth.md` — git credential setup.
