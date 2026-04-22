# ADR-006: Production secrets + migration architecture

- **Status:** Proposed
- **Date:** 2026-04-22
- **Authors:** Gurinder Singh
- **Reviewers:** Gurinder Singh (repository owner)
- **Supersedes:** none (closes 3E carry-forwards; supersedes `MedcoreAppPasswordSync`'s operational semantics)
- **Related:** ADR-001 (PostgreSQL + Flyway); ADR-002 (OIDC-only identity); ADR-004 (tier model); ADR-005 (product direction framework)

---

## 1. Context

Phase 3E (commit `e690275`) made Medcore's application process
connect as `medcore_app` (non-superuser) at runtime — the first
real RLS-enforcement win. But that slice landed two acknowledged
residuals:

1. **Flyway runs in-process.** `spring.flyway.enabled=true` at
   application boot runs migrations through a Hikari pool sized
   for the migrator role. Migrator credentials live in the JVM
   pool even under the verify-only password posture. A compromised
   application process has access to privileged credentials it
   does not need to fulfil its runtime role.
2. **No distinct `medcore_migrator` role.** In local dev the
   container superuser is the migrator; in production the slice
   deferred role provisioning to ops without a canonical pattern.
3. **No central secrets abstraction.** `MEDCORE_DB_APP_PASSWORD`
   is read via `@Value` from one place; any future secret would
   re-introduce the same ad-hoc pattern.
4. **No migration-ordering invariant at deploy time.** The
   application could start with a stale schema if someone
   misorders a future CI/CD pipeline.

Phase 3E marked these as carry-forward items; Phase 3H closes them.

## 2. Decision

**We will move Flyway out of the application process for
production deployments, introduce a `SecretSource` abstraction
with fail-fast semantics, provision `medcore_migrator` as a
distinct least-privilege role via migration V11, and document
migration-before-application as a hard deployment invariant
enforced at runtime by `FlywayMigrationStateCheck`.**

### 2.1 Out-of-process Flyway

Two execution paths, **single source of truth** for configuration:

- `apps/api/flyway.conf` holds policy (schemas, locations,
  baseline / validate modes, clean-disabled). Connection details
  (URL, user, password) come from env vars; no secrets committed.
- **Gradle Flyway plugin (dev / CI)** — engineers run
  `./gradlew flywayMigrate` against local Postgres.
  `apps/api/build.gradle.kts` configures the plugin to match the
  shared `flyway.conf`.
- **Flyway CLI container (production)** —
  `docker/flyway-migrator/Dockerfile` builds a production-only
  image based on `flyway/flyway:10.20.1`, copies the same
  `flyway.conf` and the `db/migration/**` tree, runs `flyway
  migrate` by default. Gradle plugin version pinned to
  `10.20.1` in `build.gradle.kts` so dev/CI and prod apply
  identical Flyway versions.

**Production deployments set `MEDCORE_APP_RUN_MIGRATIONS=false`**.
The application's `spring.flyway.enabled` resolves to false; the
application no longer runs migrations at boot. The deploy
pipeline (Phase 3I — Kubernetes `initContainer` or ECS pre-deploy
task) runs the Flyway CLI container against the target database
before the application task starts.

### 2.2 `SecretSource` abstraction

```kotlin
interface SecretSource {
    fun get(key: String): String       // throws on missing/blank
    fun getOrNull(key: String): String?
}
```

Three implementations land in 3H:

- **`EnvVarSecretSource`** (default `@Component`) — reads via
  Spring `Environment` so env vars, system properties, and YAML
  resolve uniformly. Production-suitable for deployments that
  inject secrets as env vars.
- **`StaticSecretSource`** — test-only in-memory map. Not
  `@Component`; instantiated directly in test configs.
- **`AwsSecretsManagerSecretSource`** — stub that throws
  `NotImplementedError` with a `TODO(3I)` pointer. Its real
  implementation lands with Phase 3I alongside Terraform and
  AWS IAM wiring.

`SecretValidator` runs in `@PostConstruct` and calls
`secretSource.get(key)` on every entry in `REQUIRED_SECRETS`.
Missing any secret aborts Spring context refresh — the
application never reaches `ApplicationStartedEvent`, never
reaches `ApplicationReadyEvent`, readiness stays DOWN.

### 2.3 `medcore_migrator` role — least privilege

Migration `V11__medcore_migrator_role.sql` creates the role
idempotently with attributes `LOGIN NOINHERIT NOBYPASSRLS
NOCREATEDB NOCREATEROLE NOSUPERUSER NOREPLICATION`. Grants:

| Capability | Grant |
|---|---|
| Read / write flyway_schema_history | `ALL ON ALL TABLES IN SCHEMA flyway` |
| Land DDL in application schemas | `USAGE, CREATE ON SCHEMA identity/tenancy/audit` |
| Read application tables | **REVOKE ALL** — migrator does DDL, not data access |
| Bypass RLS | **NOBYPASSRLS** — RLS policies still apply |
| Create users | **NOCREATEROLE** |

Password is NOT set in the migration — ops concern. In local dev,
the existing `MedcoreAppPasswordSync` SYNC path (deprecated, local-
only) continues to set the password; production ops use AWS
Secrets Manager + IAM auth (Phase 3I).

### 2.4 Startup-ordering invariant (operational)

**No application component may perform DB-dependent work before
`FlywayMigrationStateCheck` completes.** This slice enforces the
invariant via
[EntityManagerFactoryDependsOnPostProcessor](../../apps/api/src/main/kotlin/com/medcore/platform/persistence/FlywayMigrationStateCheck.kt),
which makes JPA's `EntityManagerFactory` wait on the check.
Components outside the JPA path (a future `@Async` handler,
background executor, `CommandLineRunner`, etc.) are NOT yet
covered by that post-processor. A future slice introducing any
such component MUST either:

1. Declare `@DependsOn("flywayMigrationStateCheck")` on the bean, OR
2. Introduce a new post-processor that wires the relevant
   factory bean to the check, OR
3. Document a clear rationale for exempting the component (e.g.,
   it genuinely runs before the DB is touched).

Documented here so the invariant survives future slices that add
non-JPA boot paths.

### 2.5 Migration-before-application invariant

`FlywayMigrationStateCheck` — active only when
`spring.flyway.enabled=false` — queries
`flyway.flyway_schema_history` at `@PostConstruct` and compares
to a compile-time `MIN_EXPECTED_INSTALLED_RANK` constant. Stale
schema aborts context refresh with a clear message:

```
REFUSING TO START: latest applied migration rank (N) is below the
expected minimum (M). Run Flyway migrations before starting the
application. See docs/runbooks/secrets-and-migrations.md §Deployment
sequence.
```

`JpaDependsOnFlywayCheck` makes JPA's `EntityManagerFactory`
depend on the check, so Hibernate's `ddl-auto=validate` cannot
race ahead.

### 2.5 `MedcoreAppPasswordSync` deprecation

The VERIFY path from Phase 3E moves to `SecretValidator`. The SYNC
path (local-only `ALTER ROLE medcore_app WITH PASSWORD`) remains
for dev ergonomics, now gated by a **production-issuer guard**:
SYNC refuses to run if the configured OIDC issuer does not match
a local pattern (`localhost`, `127.0.0.1`, `[::1]`,
`mock-oauth2-server`). Defence in depth against a misconfigured
staging/prod env inheriting the dev `passwordSyncEnabled=true`.

`MedcoreAppPasswordSync` and `JpaDependsOnPasswordCheck` are
annotated `@Deprecated`; both are scheduled for removal in Phase
3I once `AwsSecretsManagerSecretSource` is real and local-dev
uses the abstraction too.

## 3. Alternatives Considered

### 3.1 Keep Flyway in-process

**What:** Leave Phase 3E's posture untouched. Mitigate the
credentials-in-JVM residual by running Flyway under a separate
`@ConditionalOnProfile("migrate")` profile — start the app, run
migrations, shut down; then start the app for real.

**Why rejected:** Still ships migrator credentials in the same
image. Still creates a "migrate" profile-mode that's a shadow
deployment path. Migration success / failure signal is buried in
application-start logs. Out-of-process is the clearer model.

### 3.2 Flyway CLI only (no Gradle plugin)

**What:** Engineers run the Flyway CLI locally (via Docker or
brew-installed CLI) for dev.

**Why rejected:** Requires every dev to install / run a separate
tool. A Gradle task is a one-liner (`./gradlew flywayMigrate`)
and integrates with existing CI runners. Dual-path with strict
config-parity is the trade-off.

### 3.3 HashiCorp Vault instead of AWS Secrets Manager

**What:** Use Vault as the secret store.

**Why rejected:** `00-vision.md` §6.3 names AWS as the target
cloud and AWS Secrets Manager as the target secret store. Vault
is a supplementary option if a future customer / deployment
requires it (no one does today). `SecretSource` is abstract
enough that a `VaultSecretSource` could land later without
refactoring callers.

### 3.4 ORM-level schema validation only (no FlywayMigrationStateCheck)

**What:** Let Hibernate's `ddl-auto=validate` be the only schema-
state check.

**Why rejected:** Hibernate's error on a stale schema is a
low-level JDBC / SQL error ("column not found") that doesn't
guide operators to the fix. A dedicated check with a clear
runbook pointer is worth the 80 lines of code.

### 3.5 `SecretSource` bound to Spring Environment directly (no custom interface)

**What:** Skip the interface; every caller uses Spring's
`Environment.getProperty()` with a null-check pattern.

**Why rejected:** Phase 3I introduces AWS Secrets Manager as a
separate property source. Without `SecretSource`, every caller
that accesses a secret-material key must know which source to
consult OR trust Spring's resolution order. A dedicated abstraction
lets callers stay source-agnostic and lets the test harness
substitute a deterministic in-memory source.

## 4. Consequences

### 4.1 Positive

- Production JVM no longer holds migrator credentials at runtime.
- Migration failures fail-loudly with a dedicated deploy task, not
  buried in application-start logs.
- `medcore_migrator` enforces least privilege at the DB level —
  a compromised migrator cannot read application data.
- `SecretSource` is the seam for Phase 3I's AWS Secrets Manager
  integration without refactoring callers.
- `SecretValidator` + `FlywayMigrationStateCheck` both abort
  context refresh; misconfigured deployments never reach readiness.
- Shared `flyway.conf` eliminates dev-vs-prod config drift.
- Password rotation is a documented, rehearsed procedure — no
  longer tribal knowledge.

### 4.2 Negative

- Dev's mental model gains a new dimension ("is Flyway in-process
  or out-of-process?"). Mitigated by the runbook and the fact that
  dev default stays in-process.
- Production deployment pipeline gains a step (migration task
  before app task). Runbook covers it; Phase 3I's Terraform wires
  it into the ECS / Kubernetes definition directly.
- Two Flyway versions to keep aligned — Gradle plugin and CLI
  container. Version pinned explicitly in both places; runbook
  names this as a maintenance point.

### 4.3 Risks & Mitigations

- **Dev runs migration against prod by accident.** Mitigation:
  Gradle plugin URL defaults to localhost; env vars override
  explicitly. Runbook calls out the risk.
- **Migration CLI container uses stale migrations.** Mitigation:
  Dockerfile copies from the committed source tree; CI builds
  the image on every merge.
- **`medcore_migrator` password rotated but Flyway container env
  not updated.** Mitigation: runbook's rotation procedure
  includes a verification step that re-runs `flyway info`
  before declaring rotation complete.
- **Missing env var causes boot failure at 3am.** Mitigation:
  `SecretValidator`'s error message names the key and points at
  the runbook. Operators see the actionable message, not a
  generic NPE.

## 5. Compliance & Security Impact

- **HIPAA Security Rule §164.312(a)(1) / (d)** (access control /
  person-or-entity authentication): `medcore_migrator` is a
  distinct role with documented, least-privilege grants. Phase 3I
  completes the picture with managed secret rotation.
- **HIPAA §164.312(e)** (transmission security): no change; TLS
  applies to the migrator connection the same as the application
  connection.
- **SOC 2 CC6.1** (logical access): role separation between
  application and migrator is enforced at the DB level.
  Credentials for each are managed separately.
- **SOC 2 CC6.6** (vulnerability management): OTel-visible
  startup sequence makes it obvious when migrations run; a
  future SLO / alert on migration duration / failure lands in
  Phase 3I observability wiring.
- **SOC 2 CC7.1** (change management): Flyway version pinning +
  config parity + CI-checkable migration history give evidence
  of controlled schema change.
- **21 CFR Part 11** (if Medcore ever handles regulated clinical-
  trial data): migration chain + role separation + rotation
  procedure support audit-trail and access-control requirements.
  No direct coverage today; scoped for Phase 7+ when / if a
  research use case appears.

## 6. Operational Impact

### 6.1 New deployment sequence

Production deploys MUST run:

1. Build the application container AND the Flyway migrator
   container.
2. Run the migrator container against the target database. Wait
   for exit code 0.
3. Only after migrator success, roll out the application
   container (initContainer in Kubernetes; pre-deploy task in
   ECS).

### 6.2 Password rotation

Rotation is a manually-rehearsed procedure (evidence captured to
`docs/evidence/`) for both `medcore_app` and `medcore_migrator`.
Rehearsed once in Phase 3H against local Postgres; scheduled
quarterly in production after Phase 3I lands. Automated rotation
is Phase 7+ (compliance operationalisation).

### 6.3 Secret inventory — single source of truth

`SecretValidator.REQUIRED_SECRETS` is the compile-time list of
secrets the application MUST have. Adding a new secret requires
updating:

1. `REQUIRED_SECRETS` list.
2. `docs/runbooks/secrets-and-migrations.md` secrets inventory
   table.
3. `SecretValidatorTest` coverage.
4. This ADR if the new secret represents a new subsystem.

Same four-way-sync pattern as the Phase 3F.2 attribute allow-list
and the `ErrorCodes` registry from Phase 3G.

## 7. Rollout Plan

Phase 3H itself lands in one Tier 3 commit:
- `SecretSource` abstraction + three implementations
- `SecretValidator`
- `FlywayMigrationStateCheck` + `JpaDependsOnFlywayCheck`
- V11 migration (additive role creation + grants)
- `MedcoreAppPasswordSync` deprecated + production-issuer guard
- Flyway Gradle plugin configuration
- `apps/api/flyway.conf` (single source of truth)
- `docker/flyway-migrator/` (CLI container)
- Unit + integration tests (~23 new)
- ADR-006 (this file)
- `docs/runbooks/secrets-and-migrations.md`
- `docs/security/phi-exposure-review-3h.md`
- Rotation rehearsal evidence `docs/evidence/password-rotation-rehearsal-YYYY-MM-DD.md`
- 03-DoD §3.1 Phase 3H row

Phase 3I (next): Terraform for ECS + RDS + AWS Secrets Manager,
real `AwsSecretsManagerSecretSource` implementation,
`MedcoreAppPasswordSync` deletion.

Phase 3J (next): tenancy writes + RBAC — depends on Phase 3H's
role-separation work.

## 8. Acceptance

Closed by:
- [x] Out-of-process Flyway execution path (Gradle + CLI container)
- [x] `SecretSource` abstraction with fail-fast + three
      implementations
- [x] V11 provisions `medcore_migrator` with least-privilege
      grants + NOBYPASSRLS attribute
- [x] `FlywayMigrationStateCheck` aborts boot on stale schema
- [x] `MedcoreAppPasswordSync` deprecated with production-issuer
      guard on SYNC
- [x] Shared `apps/api/flyway.conf`
- [x] Password rotation runbook + rehearsal evidence

## 9. References

- ADR-001 (PostgreSQL + Flyway + row-level tenancy)
- ADR-002 (OIDC-only identity)
- ADR-004 (tiered execution authority)
- ADR-005 (product direction framework)
- `docs/runbooks/secrets-and-migrations.md` (operator procedures)
- `docs/runbooks/local-services.md` §12 (local Postgres start/stop)
- Phase 3E commit `e690275` (runtime role switch — residuals addressed here)
