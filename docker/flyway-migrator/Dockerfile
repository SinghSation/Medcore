# Phase 3H (ADR-006) — Production Flyway migrator container.
#
# Runs Medcore's Flyway migrations out-of-process, so the
# application JVM never holds migrator credentials. Invoked
# ahead of the application task in any production-style
# deployment (Kubernetes initContainer, ECS one-shot task,
# similar patterns).
#
# Version pinning:
#   - Base image tag MUST match the Gradle plugin version in
#     `apps/api/build.gradle.kts` (plugins { id("org.flywaydb.flyway") }).
#     Dev/CI and prod run identical Flyway versions against the
#     identical migration files.
#
# Secrets:
#   - MEDCORE_DB_URL / MEDCORE_DB_MIGRATOR_USER /
#     MEDCORE_DB_MIGRATOR_PASSWORD are injected by the deployment
#     substrate (AWS Secrets Manager, Kubernetes Secrets, or
#     equivalent). This image MUST NOT bake any secret material.
#
# Non-goals:
#   - No application code. No JRE. No Gradle. A clean runtime that
#     only understands how to apply SQL migrations.
#   - No access to AWS Secrets Manager directly from the container
#     (that's ops-layer plumbing, not this image's concern).

FROM flyway/flyway:10.20.1

# Copy the single-source-of-truth config file. The Flyway CLI reads
# `/flyway/conf/*.conf` automatically; no explicit -configFiles flag
# needed.
COPY apps/api/flyway.conf /flyway/conf/flyway.conf

# Copy every migration tree. The schema layout matches the
# application resource layout so there is no "container builds
# differently than the app" drift.
COPY apps/api/src/main/resources/db/migration/identity /flyway/sql/identity
COPY apps/api/src/main/resources/db/migration/tenancy  /flyway/sql/tenancy
COPY apps/api/src/main/resources/db/migration/audit    /flyway/sql/audit
COPY apps/api/src/main/resources/db/migration/flyway   /flyway/sql/flyway

# Override flyway.conf's `filesystem:src/main/resources/...` paths
# for the CLI's in-container layout. The CLI's `locations` flag
# wins over the config file's.
ENV FLYWAY_LOCATIONS=filesystem:/flyway/sql/identity,filesystem:/flyway/sql/tenancy,filesystem:/flyway/sql/audit,filesystem:/flyway/sql/flyway

# Default command applies migrations. Override at runtime to
# `info`, `validate`, etc. as needed.
CMD ["migrate"]
