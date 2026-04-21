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

## 11. Related

- **ADR-001** — PostgreSQL + Flyway + row-level tenancy.
- `.cursor/rules/03-db-migrations.mdc` — migration rules (applies
  once Flyway lands in Phase 3A Step 2).
- `docs/runbooks/development.md` — broader local dev setup.
- `docs/runbooks/github-auth.md` — git credential setup.
