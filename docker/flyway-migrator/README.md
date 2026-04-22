# Flyway migrator container

Phase 3H (ADR-006) — production-only container image that runs
Medcore's Flyway migrations out-of-process. See
`docs/runbooks/secrets-and-migrations.md` for operator procedures.

## Build

From the repository root:

```bash
docker build \
  -t medcore/flyway-migrator:$(git rev-parse --short HEAD) \
  -f docker/flyway-migrator/Dockerfile \
  .
```

## Run

```bash
docker run --rm --network host \
  -e MEDCORE_DB_URL="jdbc:postgresql://host:5432/medcore" \
  -e MEDCORE_DB_MIGRATOR_USER="medcore_migrator" \
  -e MEDCORE_DB_MIGRATOR_PASSWORD="<from-secret-store>" \
  medcore/flyway-migrator:<tag> \
  migrate
```

Override the command to `info`, `validate`, etc. as needed.

## Configuration — single source of truth

The image copies `apps/api/flyway.conf` (committed) to
`/flyway/conf/flyway.conf` inside the container. The Gradle
Flyway plugin (`apps/api/build.gradle.kts`) uses the same
config. Any drift between the two is a production-migration
incident waiting to happen.

## Version pinning

The base image tag (`flyway/flyway:10.20.1`) MUST match the
Gradle plugin version in `apps/api/build.gradle.kts`. Update
both in lockstep via a dedicated slice.
