# PHI Exposure Review — Phase 3H (Secrets + Production Posture)

**Slice:** Phase 3H — `SecretSource` abstraction (3 impls),
`SecretValidator`, `FlywayMigrationStateCheck` + JPA dependency
post-processor, V11 migration (medcore_migrator role),
`MedcoreAppPasswordSync` deprecated with production-issuer guard,
Gradle Flyway plugin + shared `flyway.conf`, Flyway CLI container,
operator runbook, ADR-006.

**Reviewer:** Repository owner (solo).
**Date:** 2026-04-22.
**Scope:** All Phase 3H code + infra + config. Secrets are
access-control material, not PHI — this review confirms no PHI
path is introduced, weakened, or exposed by the credential- and
migration-handling changes.

**Risk determination:** None.

---

## 1. What this slice handles

Phase 3H adds machinery for credential and migration management.
The surface is entirely infrastructure:

- **Database passwords** (app role, migrator role) — access-
  control material. Not PHI.
- **OIDC issuer URI** — endpoint URL. Not PHI.
- **Flyway migration SQL files** — schema definitions, role
  creation, grants. Not PHI. (Medcore's migrations never contain
  patient data; fixtures come from Synthea in future phases.)
- **Flyway schema history rows** — version numbers, script names,
  checksums. Not PHI.

No clinical data touches this slice. Medcore is still pre-Phase-
4A — no patient entity exists yet.

---

## 2. Secret material handling

`SecretSource` and its implementations are the new surfaces.
Field-by-field analysis:

### `EnvVarSecretSource`

- Reads via Spring `Environment` — never logs the resolved value
  at any level.
- Error messages from `get()` name the KEY ("missing env var
  `medcore.db.app.password`") but never the VALUE. Key names are
  operational metadata, not PHI.

### `StaticSecretSource` (test-only)

- Constructor takes a `Map<String, String>`. Tests populate it
  with throwaway test values (e.g., `"non-blank-value"` in
  `SecretValidatorTest`).
- Never used outside the test source set; `@Component` annotation
  intentionally absent.

### `AwsSecretsManagerSecretSource` (stub)

- Throws `NotImplementedError` on every call. No live credential
  handling in 3H.
- `@Component` absent — ops must explicitly wire it in Phase 3I.

### `SecretValidator`

- Logs the COUNT of missing secrets and the LIST OF KEYS at
  ERROR.
- Does NOT log resolved values.
- Error message names the runbook for operator guidance.

### `MedcoreAppPasswordSync` (deprecated in 3H)

- SYNC path binds the password value as a JDBC parameter (`?`)
  inside a `pg_temp` function. No Kotlin string interpolation of
  secret material. No log line emits the password.

---

## 3. Migration content review

`V11__medcore_migrator_role.sql`:

- `CREATE ROLE medcore_migrator WITH LOGIN NOINHERIT NOBYPASSRLS
  ...` — role attributes only. No embedded password.
- `GRANT USAGE, CREATE ON SCHEMA ...` — schema-level grants.
  Metadata.
- `REVOKE ALL ON ALL TABLES IN SCHEMA identity/tenancy/audit
  FROM medcore_migrator` — least-privilege enforcement.
- `GRANT USAGE ON SCHEMA flyway TO medcore_app` + `GRANT SELECT
  ON flyway.flyway_schema_history TO medcore_app` — minimum for
  `FlywayMigrationStateCheck`.

No INSERT / UPDATE / DELETE. No data-carrying SQL.

---

## 4. Infrastructure file review

### `apps/api/flyway.conf`

- Contains policy (locations, schemas, baseline/validate).
- Connection fields use env-var substitution (`${MEDCORE_DB_URL}`,
  etc.) — no secrets.
- Committed to the repo.

### `docker/flyway-migrator/Dockerfile`

- Base image `flyway/flyway:10.20.1`.
- `COPY` steps embed the committed `flyway.conf` + migration SQL.
- `ENV FLYWAY_LOCATIONS=...` — in-container path override, not a
  secret.
- No `ARG` or `ENV` for passwords. No secret baking.

### `docs/runbooks/secrets-and-migrations.md`

- Documents the secret inventory by KEY (not value).
- Rotation examples use shell-variable placeholders (`$NEW_PWD`),
  not literal secrets.
- Rehearsal evidence template captures ROTATION PROCEDURE (not
  password values).

---

## 5. Log emission review

New log sites in Phase 3H:

- `SecretValidator.validate()` — INFO on success ("all N required
  secrets present via <source class>"), ERROR on failure ("N
  required secret(s) missing: [<keys>]"). No values.
- `FlywayMigrationStateCheck.check()` — INFO on pass ("schema at
  rank N (expected >= M)"), ERROR on unreadable history. No row
  content, no SQL.
- `MedcoreAppPasswordSync.afterPropertiesSet()` — DEBUG when sync
  disabled; INFO when sync mode activated; ERROR via
  `IllegalStateException` when production-issuer guard refuses.
  No password value in any branch.

`TracingPhiLeakageTest` (Phase 3F.2) continues to assert no
`medcore.*` observation attribute carries PHI-shaped values. This
slice adds no new Medcore-custom observations.

---

## 6. Attack-surface considerations

### 6.1 Leaked env var in a process listing

A compromised host could `ps auxe` and read the env vars of the
application process. Mitigation: standard Kubernetes / ECS
deployment patterns (Secrets objects, task-def `valueFrom`) mount
env vars through the orchestrator's secret substrate, not via
plaintext in task definitions or manifests.

### 6.2 Migrator container compromise

If the Flyway CLI container is compromised while running, the
attacker has `medcore_migrator` credentials. Mitigation:
`medcore_migrator` has NO ability to read application data
(REVOKE ALL ON ALL TABLES in identity/tenancy/audit). A
compromised migrator can only DDL — destructive, but no data
exfil.

### 6.3 Flyway SQL injection via migration file

If an attacker lands a migration file in a PR, they could inject
arbitrary SQL. Mitigation: migration files go through standard
PR review under Tier 3 governance (ADR-004). Non-additive
migrations (REVOKEs, DROPs, ALTERs) receive additional scrutiny.

### 6.4 Password rotation race

During rotation, the old password is valid simultaneously with
the new one briefly. A rolling restart catches new pods with the
new password; old pods with old connections keep working until
they reconnect. Mitigation: the runbook rotation procedure
documents the overlap window; ALTER ROLE is atomic but
connection pools may hold old connections open.

### 6.5 `MEDCORE_DB_APP_PASSWORD_SYNC_ENABLED` flag inherited to
prod

Phase 3E gated the SYNC path on this env var. An env file copied
from dev to staging could carry the flag with no OIDC issuer
change, triggering SYNC against a shared staging DB. Mitigation:
Phase 3H adds the production-issuer guard — SYNC now refuses
unless OIDC issuer matches a local pattern. Belt-and-braces on
top of the flag.

---

## 7. Conclusion

Phase 3H introduces no new PHI paths. Secret handling is
confined to access-control material, with KEY-level logging only
(never values), fail-fast semantics that refuse silent
misconfiguration, and a clean abstraction seam for Phase 3I's
AWS Secrets Manager integration. Migration SQL is schema +
grants only, no data. Infrastructure files embed no secrets.

**Risk: None.**
