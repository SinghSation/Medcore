# Password rotation rehearsal — 2026-04-22

- **Date:** 2026-04-22
- **Operator:** Gurinder Singh (repository owner)
- **Environment:** local (docker-compose Postgres)
- **Roles rotated:** `medcore_app`, `medcore_migrator`
- **Purpose:** first rehearsal of the Phase 3H rotation procedure
  from `docs/runbooks/secrets-and-migrations.md`, providing
  evidence that the documented procedure is executable and
  produces the expected outcome.

## Steps executed

1. Started local Postgres via `docker-compose up -d postgres`.
2. Applied migrations through V11 via `./gradlew flywayMigrate`
   (Phase 3H Gradle plugin path).
3. Generated a new password for `medcore_app`:
   ```
   openssl rand -base64 32 | tr -d '=+/' | head -c 40
   ```
4. Rotated `medcore_app` password via the migrator connection:
   ```sql
   ALTER ROLE medcore_app WITH PASSWORD '<new-value>';
   ```
5. Updated the `MEDCORE_DB_APP_PASSWORD` env var in the local
   shell.
6. Restarted the application via `./gradlew :apps:api:bootRun`.
7. Verified authenticated `/api/v1/me` succeeded.
8. Repeated steps 3–6 for `medcore_migrator` (substituting role
   name and env var `MEDCORE_DB_MIGRATOR_PASSWORD`).
9. Smoke-tested the Flyway migrator container with the new
   migrator password:
   ```
   docker run --rm --network host \
     -e MEDCORE_DB_URL=... \
     -e MEDCORE_DB_MIGRATOR_USER=medcore_migrator \
     -e MEDCORE_DB_MIGRATOR_PASSWORD='<new-migrator>' \
     medcore/flyway-migrator:phase-3h-rehearsal \
     info
   ```
   Output showed `installed_rank = 11` and zero pending
   migrations — chain intact.

## Before-state verification

```sql
SELECT NOW(), rolname, rolvaliduntil
  FROM pg_roles
 WHERE rolname IN ('medcore_app', 'medcore_migrator');
```

- `medcore_app` — `rolvaliduntil = NULL` (password set via Phase
  3E SYNC path).
- `medcore_migrator` — `rolvaliduntil = NULL` (password set
  out-of-band via initial V11 rehearsal).

## After-state verification

Same query showed both roles still present, both still
`rolvaliduntil = NULL` (unchanged — rotation replaces the
password hash, not the validity window). Authenticated request
against the restarted application succeeded; `Flyway info` via
the CLI container succeeded.

## Failure scenarios tested

- **Old password no longer works:** CONFIRMED. A deliberate
  attempt to connect with the old `medcore_app` password after
  rotation returned `password authentication failed for user
  "medcore_app"` from Postgres. Application restart picks up the
  new env var value and proceeds.
- **New password logs expected:** CONFIRMED. Startup logs showed
  `[SECRETS] SecretValidator: all 1 required secrets present via
  EnvVarSecretSource` followed by a clean Hibernate schema-
  validation pass.
- **Flyway history intact:** CONFIRMED. `flyway_schema_history`
  rowcount and checksums unchanged after rotation.
- **Production-issuer guard refuses non-local SYNC:** CONFIRMED
  separately via `MedcoreAppPasswordSyncTest.sync enabled against
  non-local issuer refuses with ADR-006 citation`.

## Outcome

- **Rotation completed:** yes
- **Unexpected behaviour:** none
- **Runbook accuracy:** the documented procedure in
  `docs/runbooks/secrets-and-migrations.md` §4 matches the
  executed steps 1:1. No runbook corrections needed.
- **Next rehearsal due:** 2026-07-22 (quarterly cadence per
  `04-compliance-and-legal.md` §Compliance maturity).

## Signature

Rehearsal executed against local Postgres only. Production
rotation follows the same procedure substituting AWS Secrets
Manager (Phase 3I) for `ALTER ROLE` shell commands once
`AwsSecretsManagerSecretSource` lands.
