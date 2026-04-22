plugins {
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    kotlin("plugin.jpa") version "2.0.21"
    // Phase 3H (ADR-006) — out-of-process Flyway for production. The
    // Gradle plugin is the dev/CI face of the same migration
    // execution path the Flyway CLI container runs in production.
    // Both consume `apps/api/flyway.conf` so policy is never
    // duplicated. Pinned to 10.20.1 to match the CLI container
    // `docker/flyway-migrator/Dockerfile` image tag.
    id("org.flywaydb.flyway") version "10.20.1"
}

group = "com.medcore"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Phase 3F.3 — health, liveness, readiness, info endpoints under
    // /actuator/**. Only `health` and `info` are exposed on the web
    // (see application.yaml `management.endpoints.web.exposure.include`);
    // all other actuator endpoints (env, metrics, beans, etc.) remain
    // explicitly unexposed and return 404. BOM-managed version.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Phase 3F.2 — OpenTelemetry traces + metrics via Micrometer.
    // micrometer-tracing-bridge-otel wires Micrometer Observation to
    // the OpenTelemetry SDK; opentelemetry-exporter-otlp ships spans /
    // metrics over OTLP when endpoints are configured (disabled by
    // default — unset endpoint = collected-but-not-exported);
    // aspectjweaver enables @Observed annotation processing via the
    // ObservedAspect bean registered in ObservedAspectConfig.
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    runtimeOnly("io.micrometer:micrometer-registry-otlp")
    implementation("org.aspectj:aspectjweaver")
    implementation("org.flywaydb:flyway-core")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("no.nav.security:mock-oauth2-server:2.1.10")
    // Phase 3F.2 — in-memory OTel SDK for PHI-leakage assertions
    // against span attributes. Test-only; not shipped.
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    // Phase 3I.1 — machine-enforced architectural invariants
    // (WriteGate exclusivity, repository-access perimeter, module
    // boundaries, audit discipline). Rules live in
    // apps/api/src/test/kotlin/com/medcore/architecture/.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// --- Phase 3H: Flyway CLI configuration ---
//
// Reads `apps/api/flyway.conf`. Engineers run `./gradlew flywayMigrate`
// against local Postgres (set MEDCORE_DB_URL / MEDCORE_DB_MIGRATOR_USER /
// MEDCORE_DB_MIGRATOR_PASSWORD in the shell first). Production runs
// the same migrations via `docker/flyway-migrator/` against the same
// config file.
flyway {
    // `configFiles` would let Flyway load every property from the
    // .conf file, but the Gradle plugin version (10.20.1) currently
    // resolves filesystem-relative paths against the root project
    // root, not the subproject. Mirror the relevant properties here
    // so the Gradle task works regardless of invocation dir. The
    // runbook flags this as a known quirk.
    url = System.getenv("MEDCORE_DB_URL")
        ?: "jdbc:postgresql://localhost:5432/medcore_dev"
    user = System.getenv("MEDCORE_DB_MIGRATOR_USER")
        ?: System.getenv("POSTGRES_USER")
        ?: "medcore"
    password = System.getenv("MEDCORE_DB_MIGRATOR_PASSWORD")
        ?: System.getenv("POSTGRES_PASSWORD")
        ?: ""
    schemas = arrayOf("flyway", "identity", "tenancy", "audit")
    defaultSchema = "flyway"
    locations = arrayOf(
        "filesystem:src/main/resources/db/migration/identity",
        "filesystem:src/main/resources/db/migration/tenancy",
        "filesystem:src/main/resources/db/migration/audit",
        "filesystem:src/main/resources/db/migration/flyway",
    )
    baselineOnMigrate = false
    validateOnMigrate = true
    cleanDisabled = true
}
