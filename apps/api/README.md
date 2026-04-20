# apps/api

Medcore backend — **Kotlin + Spring Boot 3.4 on Java 21**, built with Gradle
Kotlin DSL. This is the platform shell only: no domain, no auth, no
persistence. Domain modules are added under `com.medcore.<module>` as they
land, each following `.claude/skills/new-module.md`.

## Quickstart

From the repo root:

```bash
make api-dev      # http://localhost:8080
make api-test     # JUnit 5
make api-build    # build/libs/*.jar
```

First-time setup (generates the Gradle wrapper, requires `gradle` on PATH):

```bash
cd apps/api
gradle wrapper --gradle-version 8.11.1
```

After that, `./gradlew` is self-contained.

## Verification

No HTTP endpoints are exposed at bootstrap. To confirm the shell is
healthy:

- `make api-dev` — the Spring Boot log MUST print
  `Started MedcoreApiApplication in <n>s`.
- `make api-test` — the `spring context loads` test MUST pass.

A real health surface (Actuator or a first-party endpoint) will be added
with a dedicated ADR when auth and observability land.

## Layout

```
apps/api/
  build.gradle.kts
  settings.gradle.kts
  gradle.properties
  src/main/kotlin/com/medcore/MedcoreApiApplication.kt
  src/main/resources/application.yaml
  src/test/kotlin/com/medcore/MedcoreApiApplicationTests.kt
```

## Conventions

- Base package: `com.medcore`.
- New modules: `com.medcore.<module>`, each with its own package, tests, and
  (when applicable) migrations under `apps/api/src/main/resources/db/migration/<module>/`.
- Contracts are authored in `packages/schemas/` **before** implementation.
  See `.claude/skills/api-contract-first.md`.
- No feature code is added to this shell until the relevant ADR and
  contract exist.
