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

## 14. Runtime datasource role switch (Phase 3E)

> **Phase 3E flips the running application from a superuser
> datasource to `medcore_app`.** RLS policies installed by V8 are
> now enforced for live request traffic, not just for tests that
> connect directly as `medcore_app`. This closes the deferred
> runtime-enforcement gap from Phase 3D.

### Datasource model

There are now TWO database connections at runtime:

- **Application datasource** (`spring.datasource.*`) connects as
  `medcore_app` — non-superuser, RLS-bound, restricted DML grants.
  JPA, all repositories, the audit writer, and the tenancy session
  context all use this connection.
- **Migrator / Flyway datasource** (`spring.flyway.*`) connects as
  the dedicated migrator role (the container superuser locally; a
  pre-provisioned `medcore_migrator` role in production). Owns
  schema DDL and all migrations.

`MedcoreAppPasswordSync` (`platform/persistence/`) runs after Flyway
and before JPA opens its first connection. Two execution modes,
controlled by `medcore.db.app.passwordSyncEnabled`:

- **`false` (production-default).** VERIFY-ONLY. Asserts
  `MEDCORE_DB_APP_PASSWORD` is non-blank and stops there. The
  application process does NOT call `ALTER ROLE`. The role's
  password is provisioned out-of-band by ops / the secret manager.
  This keeps the runtime app process from exercising
  role-rotation capability against the database.
- **`true` (local / tests).** SYNC. After verification, runs
  `ALTER ROLE medcore_app WITH PASSWORD …` against the migrator
  datasource via a parameter-bound `pg_temp` function (no string
  interpolation of the password into SQL). Convenient for
  fresh-container test runs.

Production posture: leave `MEDCORE_DB_APP_PASSWORD_SYNC_ENABLED`
unset or `false`. Ops provisions the role + password out-of-band
and the application only verifies the configured value matches
what's in the DB at connect time.

> **Residual scope.** Even in VERIFY-only mode, the application
> process still has Flyway running in-process at startup, which
> means migrator credentials live in JVM memory until Flyway
> completes its first cycle. Eliminating that residual requires
> moving Flyway out-of-process for production (CI/CD migration
> step or k8s init container) — tracked as a deferred ops slice.
> The verify/sync split addresses the immediate concern: the
> running application does not EXERCISE role-rotation capability
> in production, even though Flyway is technically in-process.

`DatabaseRoleSafetyCheck` listens for `ApplicationStartedEvent`,
queries `current_user`/`pg_roles.rolsuper` against the application
datasource, and throws `REFUSING TO START` if connected as a
superuser. The application does not begin serving traffic in that
state — closing the "I think RLS is on but the app role is super"
failure mode.

### Required environment variables

For local dev (running `make api-dev`):

```
MEDCORE_DB_APP_USER=medcore_app
MEDCORE_DB_APP_PASSWORD=<choose-a-local-password>

# Opt INTO the in-process role password sync. ONLY for local dev /
# tests. NEVER set in production — production password sync is an
# ops responsibility, not the application's.
MEDCORE_DB_APP_PASSWORD_SYNC_ENABLED=true

# Migrator credentials (default to the Postgres superuser locally)
# MEDCORE_DB_MIGRATOR_USER=medcore   # default
# MEDCORE_DB_MIGRATOR_PASSWORD=...   # falls back to POSTGRES_PASSWORD
```

For production: ops sets `MEDCORE_DB_APP_PASSWORD` from the secret
manager and provisions a dedicated `medcore_migrator` role with its
own password supplied via `MEDCORE_DB_MIGRATOR_*`.

### Quickstart for local dev

```bash
# 1. Start postgres + mock OIDC
docker compose up -d postgres mock-oauth2-server

# 2. Pick (or rotate) a local app-role password
export MEDCORE_DB_APP_PASSWORD='local-only-do-not-reuse'

# 3. Start the app
make api-dev
# Expected log lines:
#   - Flyway migrating...
#   - DatabaseRoleSafetyCheck reports current_user=medcore_app, rolsuper=false
#   - Tomcat started on port 8080
```

If the app refuses to start with `REFUSING TO START: ... superuser`,
the application config is pointing at the migrator role — check
`MEDCORE_DB_APP_USER` / `MEDCORE_DB_APP_PASSWORD`.

### Tests

`TestcontainersConfiguration` configures both datasources explicitly
and exposes a third `adminDataSource` bean (qualified) for fixtures
that need to bypass medcore_app's grant restrictions (DELETE on
audit_event between tests, INSERT on tenancy tables, ALTER ROLE,
etc.). Tests autowire it explicitly with the `adminDataSource`
qualifier; the unqualified `@Primary` datasource always resolves to
the medcore_app one so accidental misuse fails loudly.

`RuntimeRoleEnforcementTest` proves the runtime claim: the
application datasource is medcore_app (not a superuser), RLS hides
tenancy rows when no GUC is set, and live HTTP requests respect
RLS-induced visibility.

`DatabaseRoleSafetyCheckTest` proves the startup guard fires for
both passing and failing role configurations.

## 15. Related

- **ADR-001** — PostgreSQL + Flyway + row-level tenancy.
- `.cursor/rules/03-db-migrations.mdc` — migration rules (applies
  once Flyway lands in Phase 3A Step 2).
- `docs/runbooks/development.md` — broader local dev setup.
- `docs/runbooks/github-auth.md` — git credential setup.

## 16. Demo seed for Vertical Slice 1 (Phase 4B.1+)

> **Scope note.** Seeding lives in this runbook ON PURPOSE. There is
> NO seed code in the repository (no startup `DataInitializer`, no
> test-only fixture injected at runtime, no `SQL` resource in `main`).
> Seeds that ship in code paths become production hazards when someone
> later flips a flag; seeds that live in a runbook stay dev-only.

The Vertical Slice 1 demo requires a tenant, an OWNER membership for
your JIT-provisioned user, and a handful of patients before the
frontend at `http://localhost:5173` can exercise the `Login → List →
View` flow.

### 16.1 One-time prerequisites

- `docker compose up -d postgres mock-oauth2-server` (§3).
- API running via `make api-dev` (§14 `.env` + `set -a; . ./.env; set +a`).
- A signed-in browser session at `http://localhost:5173` as
  **subject `demo-user-1`** — see §16.2 below for why this is
  not optional.

### 16.2 Use a STABLE subject for every sign-in

> **Why this matters.** The `mock-oauth2-server` debugger
> auto-generates a fresh UUID in the `sub` claim every time
> you mint a token UNLESS you explicitly set the Subject
> field. Each unique `sub` creates a new `identity.user` row
> via JIT provisioning, and the demo tenant's OWNER
> membership will not follow that fresh user. Signing in
> twice without a fixed subject = two "users" = the second
> sign-in shows "No active memberships on this account".
>
> Always pin the Subject to `demo-user-1` (or a similar
> stable string) in the debugger. This is a local-dev
> contrivance; real IdPs (WorkOS, Okta, Auth0) issue stable
> `sub` claims automatically.

Open `http://localhost:8888/default/debugger` in the browser:

1. In the **Subject** input field, type `demo-user-1`.
2. Click **Issue Token**.
3. Copy the `access_token` from the Token Response box.
4. Paste it into `http://localhost:5173`'s login textarea
   and click **Sign in**.

The first sign-in creates the `identity.user` row for
`demo-user-1`. Subsequent sign-ins — as long as Subject is
the same — resolve to the same row.

### 16.3 Seed a tenant + membership + three patients

Then, in a terminal:

```bash
docker compose exec postgres \
  psql -U "${POSTGRES_USER:-medcore}" -d "${POSTGRES_DB:-medcore_dev}" <<'SQL'

BEGIN;

-- 1. Create the tenant.
INSERT INTO tenancy.tenant(
    id, slug, display_name, status,
    created_at, updated_at, row_version
) VALUES (
    gen_random_uuid(), 'acme-health', 'Acme Health', 'ACTIVE',
    NOW(), NOW(), 0
);

-- 2. Grant the JIT-provisioned `demo-user-1` an ACTIVE OWNER
--    membership on the tenant just created.
INSERT INTO tenancy.tenant_membership(
    id, tenant_id, user_id, role, status,
    created_at, updated_at, row_version
) VALUES (
    gen_random_uuid(),
    (SELECT id FROM tenancy.tenant WHERE slug = 'acme-health'),
    (SELECT id FROM identity."user" WHERE subject = 'demo-user-1'),
    'OWNER', 'ACTIVE',
    NOW(), NOW(), 0
);

COMMIT;
SQL
```

Confirm:

```bash
docker compose exec postgres \
  psql -U "${POSTGRES_USER:-medcore}" -d "${POSTGRES_DB:-medcore_dev}" \
  -c "SELECT slug, display_name FROM tenancy.tenant;"
# expected: acme-health | Acme Health
```

### 16.4 Create a handful of patients via the API

Patients go through the full write path (WriteGate + audit chain +
RLS), so create them via HTTP not SQL. Grab a bearer token using
the stable-subject flow in §16.2, then:

```bash
# Paste the token you minted in §16.2 with Subject = demo-user-1.
TOKEN='eyJraWQi...paste.here...'

# Note: birth dates MUST be >= 1900-01-01 (CreatePatientValidator
# MIN_BIRTH_DATE). Historical accuracy is not the point here —
# the demo just needs three rows. Dates below are fabricated.
for entry in \
    '{"nameGiven":"Ada","nameFamily":"Lovelace","birthDate":"1960-03-15","administrativeSex":"female"}' \
    '{"nameGiven":"Grace","nameFamily":"Hopper","birthDate":"1956-12-09","administrativeSex":"female"}' \
    '{"nameGiven":"Katherine","nameFamily":"Johnson","birthDate":"1948-08-26","administrativeSex":"female"}' ; do
  curl -fsS -X POST http://localhost:8080/api/v1/tenants/acme-health/patients \
       -H "Authorization: Bearer $TOKEN" \
       -H "X-Medcore-Tenant: acme-health" \
       -H "Content-Type: application/json" \
       -d "$entry" >/dev/null
done
```

Reload `http://localhost:5173`, click **Acme Health** on the tenants
card, and the patient list page should show three rows ordered
newest-first.

### 16.5 Wiping the demo

```bash
docker compose exec postgres \
  psql -U "${POSTGRES_USER:-medcore}" -d "${POSTGRES_DB:-medcore_dev}" <<'SQL'
BEGIN;
DELETE FROM clinical.patient_identifier;
DELETE FROM clinical.patient;
DELETE FROM clinical.patient_mrn_counter;
DELETE FROM tenancy.tenant_membership;
DELETE FROM tenancy.tenant;
DELETE FROM audit.audit_event;
DELETE FROM identity."user";
COMMIT;
SQL
```

A full `docker compose down --volumes` (§7) also wipes everything but
nukes the whole Postgres cluster along with it — use only if the
schema itself needs to be re-migrated.
