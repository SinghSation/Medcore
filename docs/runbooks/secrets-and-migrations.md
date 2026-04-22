# Secrets and Migrations — Operator Runbook

> Phase 3H (ADR-006) makes Medcore's secrets + migrations
> production-deployable. This runbook covers the operator-facing
> procedures: secrets inventory, password rotation, out-of-process
> Flyway execution, deployment sequence, and incident-response
> playbooks.

**Cadence:** updated per Phase 3H+ slice. Phase 3I replaces env-var
secrets with AWS Secrets Manager; this runbook will be superseded
in that scope.

---

## 1. Secrets inventory

| Key | Canonical Spring property | Env var | Required at boot? | Owner |
|---|---|---|---|---|
| App DB password | `medcore.db.app.password` | `MEDCORE_DB_APP_PASSWORD` | **Yes** | Ops |
| Migrator DB password | `medcore.db.migrator.password` | `MEDCORE_DB_MIGRATOR_PASSWORD` | Only when running Flyway | Ops |
| OIDC issuer URI | `medcore.oidc.issuer-uri` | `MEDCORE_OIDC_ISSUER_URI` | Yes | Ops |
| OIDC audience | `medcore.oidc.audience` | `MEDCORE_OIDC_AUDIENCE` | Optional | Ops |

`SecretValidator.REQUIRED_SECRETS` is the canonical list of boot-
required secrets. Missing any of them aborts context refresh with a
clear error message.

Adding a new secret to this list requires a synchronised update
across four surfaces:

1. `SecretValidator.REQUIRED_SECRETS` (production code).
2. `docs/runbooks/secrets-and-migrations.md` (this table).
3. `SecretValidatorTest` coverage (asserting the new key is probed).
4. ADR-006 if the new secret represents a new subsystem.

---

## 2. Deployment sequence — HARD INVARIANT

> ⚠️ **Migrations MUST complete successfully before the application
> starts.** Otherwise the application's `FlywayMigrationStateCheck`
> aborts boot with a REFUSING TO START message.

### 2.1 Kubernetes (initContainer pattern)

```yaml
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      initContainers:
        - name: flyway-migrator
          image: medcore/flyway-migrator:<git-sha>
          env:
            - name: MEDCORE_DB_URL
              value: jdbc:postgresql://postgres:5432/medcore
            - name: MEDCORE_DB_MIGRATOR_USER
              value: medcore_migrator
            - name: MEDCORE_DB_MIGRATOR_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: medcore-db
                  key: migrator-password
      containers:
        - name: medcore-api
          image: medcore/medcore-api:<git-sha>
          env:
            - name: MEDCORE_APP_RUN_MIGRATIONS
              value: "false"  # Out-of-process Flyway
            - name: MEDCORE_DB_APP_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: medcore-db
                  key: app-password
```

### 2.2 ECS (pre-deploy task)

Two task definitions:
1. **medcore-flyway-migrator** — one-shot task; runs to completion
   before step 2 starts.
2. **medcore-api** — the service; env includes
   `MEDCORE_APP_RUN_MIGRATIONS=false`.

Orchestration via a CodeBuild / GitHub Actions workflow that waits
on the migrator task's exit code before triggering the service
deploy.

### 2.3 Local dev

Local dev leaves `MEDCORE_APP_RUN_MIGRATIONS=true` (the default).
Flyway runs in-process at app start. `MedcoreAppPasswordSync`'s
SYNC path aligns the `medcore_app` role's password with the env
var (gated on local-looking OIDC issuer).

```bash
# First-time setup
docker-compose up -d postgres
export MEDCORE_DB_APP_PASSWORD=medcore_app_local_only
export MEDCORE_DB_APP_PASSWORD_SYNC_ENABLED=true
./gradlew :apps:api:bootRun
```

---

## 3. Building the Flyway migrator container

```bash
docker build \
  -t medcore/flyway-migrator:$(git rev-parse --short HEAD) \
  -f docker/flyway-migrator/Dockerfile \
  .
```

The Dockerfile copies:
- `apps/api/flyway.conf` → `/flyway/conf/flyway.conf`
- `apps/api/src/main/resources/db/migration/**` → `/flyway/sql/**`

It does NOT bake any secret material. Connection info is injected
via the env vars in §2.

### Running locally (smoke test against docker-compose Postgres)

```bash
docker run --rm --network host \
  -e MEDCORE_DB_URL="jdbc:postgresql://localhost:5432/medcore_dev" \
  -e MEDCORE_DB_MIGRATOR_USER="medcore_migrator" \
  -e MEDCORE_DB_MIGRATOR_PASSWORD="migrator-local-password" \
  medcore/flyway-migrator:$(git rev-parse --short HEAD) \
  info
```

Use `migrate` (the Dockerfile default) to apply; use `info` to
read state without applying; use `validate` to verify checksums.

---

## 4. Password rotation — procedure

### 4.1 `medcore_app` role password

```bash
# 1. Generate a new strong password (OS random source).
NEW_PWD=$(openssl rand -base64 32 | tr -d '=+/' | head -c 40)

# 2. Rotate in the DB (via migrator connection).
psql "$MEDCORE_DB_URL" -U "$MEDCORE_DB_MIGRATOR_USER" \
    -c "ALTER ROLE medcore_app WITH PASSWORD '$NEW_PWD';"

# 3. Update the secret source (AWS Secrets Manager in prod; env
#    var in dev / simple deploys).
#    [Prod example — Phase 3I:]
aws secretsmanager update-secret \
    --secret-id medcore/db/app-password \
    --secret-string "$NEW_PWD"

# 4. Trigger a rolling restart of the application service so the
#    new secret is picked up at the next pod / task.

# 5. Verify: new connections succeed; sampling log lines show
#    expected query rates.
```

### 4.2 `medcore_migrator` role password

Same procedure with `medcore_migrator` substituted. Additional
verification step:

```bash
# Smoke test that the Flyway container still works with the new password.
docker run --rm --network host \
  -e MEDCORE_DB_URL="$MEDCORE_DB_URL" \
  -e MEDCORE_DB_MIGRATOR_USER=medcore_migrator \
  -e MEDCORE_DB_MIGRATOR_PASSWORD="$NEW_MIGRATOR_PWD" \
  medcore/flyway-migrator:<current-tag> \
  info
```

### 4.3 Rotation rehearsal evidence

Every rotation rehearsal (and eventually every production rotation)
captures evidence to `docs/evidence/password-rotation-rehearsal-YYYY-MM-DD.md`
using this template:

```markdown
# Password rotation rehearsal — YYYY-MM-DD

- **Date:** YYYY-MM-DD
- **Operator:** <name>
- **Environment:** local | dev | staging | prod
- **Roles rotated:** medcore_app, medcore_migrator

## Steps executed

1. ...
2. ...
3. ...

## Before-state verification

- `SELECT NOW(), rolname, rolvaliduntil FROM pg_roles WHERE rolname IN ('medcore_app', 'medcore_migrator');`
- Output: (captured)

## After-state verification

- (same query)
- App start-up succeeded with new password: yes | no
- Flyway info succeeded with new password: yes | no

## Failure scenarios tested

- Old password no longer works: confirmed | not-tested
- New password logs expected: confirmed | not-tested
- Flyway history intact: confirmed

## Outcome

- Rotation completed: yes | no
- Unexpected behaviour: (notes)
- Next rotation due: YYYY-MM-DD
```

---

## 5. Failure playbook — `REFUSING TO START: expected Flyway state...`

App startup logs:

```
REFUSING TO START: latest applied migration rank (N) is below the
expected minimum (M). Run Flyway migrations before starting the
application. See docs/runbooks/secrets-and-migrations.md
§Deployment sequence.
```

### Recovery (3 steps)

1. **Run the Flyway migrator container** against the target
   database. Same image + env vars as the deploy pipeline:
   ```bash
   docker run --rm --network host \
     -e MEDCORE_DB_URL="$MEDCORE_DB_URL" \
     -e MEDCORE_DB_MIGRATOR_USER="$MEDCORE_DB_MIGRATOR_USER" \
     -e MEDCORE_DB_MIGRATOR_PASSWORD="$MEDCORE_DB_MIGRATOR_PASSWORD" \
     medcore/flyway-migrator:<current-tag> \
     migrate
   ```
2. **Verify success:**
   ```bash
   docker run --rm --network host \
     -e MEDCORE_DB_URL="$MEDCORE_DB_URL" \
     -e MEDCORE_DB_MIGRATOR_USER="$MEDCORE_DB_MIGRATOR_USER" \
     -e MEDCORE_DB_MIGRATOR_PASSWORD="$MEDCORE_DB_MIGRATOR_PASSWORD" \
     medcore/flyway-migrator:<current-tag> \
     info
   ```
   Expected: latest `installed_rank` ≥ the application's
   `MIN_EXPECTED_INSTALLED_RANK` constant (currently **11**).
3. **Restart the application** (Kubernetes: `kubectl rollout
   restart`; ECS: force-new-deployment). Readiness probe should
   go UP within the normal startup window.

### If step 1 fails

- `permission denied` → `medcore_migrator` password is wrong or
  role is missing. Reprovision via §4.2.
- `connection refused` → network / SG / VPC issue. Not this
  runbook's scope.
- Migration SQL error → specific migration content problem. Follow
  the standard Flyway recovery docs:
  https://documentation.red-gate.com/fd/

---

## 6. Failure playbook — `REFUSING TO START: required secret(s) missing`

App startup logs:

```
REFUSING TO START: N required secret(s) missing:
[medcore.db.app.password]. See docs/runbooks/secrets-and-migrations.md
for the secrets inventory and provisioning procedure.
```

### Recovery

- Check §1 secrets inventory for the named key's source.
- Set the corresponding env var and restart the application.
- If the secret IS set but the app still refuses to start: verify
  the env-var NAME matches the Spring-property form after relaxed
  binding (`MEDCORE_DB_APP_PASSWORD` ↔ `medcore.db.app.password`).

---

## 7. AWS Secrets Manager (Phase 3I) — forward-look

Phase 3I introduces `AwsSecretsManagerSecretSource` wired to an
AWS account's Secrets Manager via IAM role auth. At that point:

- Env-var secret injection stays available for local dev.
- Production deployments bind the SDK to the task's IAM role.
- Secret versioning + automatic rotation lands as an AWS-native
  feature.
- `MedcoreAppPasswordSync` is deleted.

---

## 8. Evidence tracking

Each rotation (rehearsal OR production) lands an evidence file
under `docs/evidence/`. Filename format:
`password-rotation-rehearsal-YYYY-MM-DD.md` or
`password-rotation-production-YYYY-MM-DD.md`.

Retention: indefinite. Evidence files serve as SOC 2 / HIPAA
audit artifacts.
