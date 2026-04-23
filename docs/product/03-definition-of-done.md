---
status: Active
last_reviewed: 2026-04-23
next_review: 2026-05-25
cadence: stable-amended-on-phase-close
owner: Repository owner
changelog:
  - 2026-04-22 — Phase 3J DoD populated alongside its first slice
    (3J.1 — WriteGate substrate). Per-sub-slice entries will land
    as each 3J.N slice opens.
---

# Medcore — Definition of Done

> Per-phase exit bar. A phase does not close because "work feels
> complete" — it closes when the per-phase DoD is met and a slice
> documents closure.
>
> Governed by [ADR-005](../adr/005-product-direction-framework.md).
> Changes to workflow-benchmark thresholds and per-phase gate
> conditions are **Tier 3** per ADR-005 §2.3.
>
> **Skeleton with populated workflow-benchmark section.** Per-phase
> detail is populated in the follow-up Tier 2 slice per ADR-005 §7
> rollout step 2.

---

## 1. Universal DoD (every phase inherits)

A slice closes only if ALL of the following are true:

- [ ] Exit criterion for the phase row in
      [`02-roadmap.md`](./02-roadmap.md) is met.
- [ ] All relevant automated tests green. Test count reported in
      the slice's review pack.
- [ ] Security controls from AGENTS.md §3 applicable to the slice
      are satisfied.
- [ ] For slices touching PHI paths (Phase 4+): PHI-exposure
      review landed in `docs/security/`.
- [ ] Audit emission at the right sites (Rule 06, ADR-003 §2).
- [ ] Commit body carries `Tier:`, `Roadmap-Phase:`, and
      carry-forward per AGENTS.md §4.7.6.
- [ ] Carry-forward items from the prior phase reconciled at
      phase-entry.

## 2. Workflow benchmarks (Phase 4+)

**Binding for every Phase 4+ slice.** A slice that introduces or
materially affects a DoD-tracked workflow MUST ship instrumentation
that measures the workflow end-to-end in the `dev` environment and
report the measured value in the review pack.

| Workflow | Target | Where measured | Phase introduced |
| ---- | ---- | ---- | ---- |
| Create patient (dashboard → saved record) | **≤ 10 seconds** | `apps/web` Playwright benchmark against `dev` with synthetic data | 4A |
| Start encounter (today's schedule → encounter started) | **≤ 3 clicks** | Click-count on a canonical scenario | 4C |
| Complete basic visit note (encounter-started → ready-to-sign) for a simple ambulatory visit | **≤ 90 seconds** | Playwright with scripted keystrokes on a SOAP template | 4D |
| Sign note (ready-to-sign → signed) | **≤ 1 click** | UI interaction count | 4D |
| Refill medication (patient chart → refill queued) | **≤ 15 seconds** | Playwright | 4E |

**Benchmarking against competitors:** measured quarterly against
Elation, Healthie, Canvas, Atlas (and any niche-adjacent competitor
surfaced by `01-competitive-landscape.md`). Results posted to
`docs/evidence/workflow-benchmarks-YYYY-QN.md`. Methodology:
identical scenario, identical operator, wall-clock and click-count
both recorded. Competitor screenshots redacted for attribution but
preserved for evidence.

**Missing a benchmark:** a slice that fails to meet its DoD
threshold does not close the phase. It becomes a carry-forward
item until the benchmark is met. Slippage of more than 20% from
target requires an ADR justifying the gap (e.g., a hard UX
constraint that the roadmap missed) and either amending the
threshold or committing to the fix.

## 3. Per-phase DoD

Each phase row in `02-roadmap.md` has an **Exit** clause. This
section expands each with the phase-specific checklist. Phases land
here as they open; pre-open phases carry a `TODO(content)` marker
until their first slice begins.

### 3.1 Phase 3F — Observability spine

Phase 3F is divided into four sub-slices; each sub-slice has its own
exit criteria and closes carry-forward items individually. The phase
itself closes when all four sub-slices have landed AND no 3F-scope
carry-forward item remains open.

#### 3.1.1 Phase 3F.1 — Request-id + structured logging

- [ ] Every inbound HTTP request carries a `request_id` in all of:
      MDC, response header (when `echo-on-response` is true),
      `audit.audit_event.request_id` column, and at least one log
      line emitted during the request lifecycle.
- [ ] Inbound `X-Request-Id` is accepted only when it matches the
      configured format regex AND is within the configured
      max-length; malformed/overlong values are replaced by a fresh
      UUIDv4 without error.
- [ ] Proxy-aware `client_ip` resolver in place:
  - Empty trusted-proxy list → `X-Forwarded-For` is IGNORED and
    `remote_addr` is returned verbatim (dev / test default).
  - Non-empty trusted-proxy list → XFF is walked right-to-left and
    the first untrusted entry is returned as client IP.
- [ ] MDC carries `request_id`, `tenant_id` (when header-resolved),
      `user_id` (when authenticated) throughout the request thread
      lifetime; cleared in a `finally` block so no value leaks to
      pooled threads.
- [ ] Structured JSON logging enabled via Spring Boot 3.4 built-in
      (`logging.structured.format.console`). Default format `ecs`;
      override via `MEDCORE_LOG_FORMAT`.
- [ ] `RequestIdAuditCorrelationTest` passes end-to-end — the
      single load-bearing test for this sub-slice — proving a single
      id appears across response header, MDC, log line, and audit
      row.
- [ ] `LogPhiLeakageTest` passes — asserts known PHI/credential
      tokens (bearer, email, display name) do not appear in emitted
      log output.
- [ ] Structured logging contract asserted without over-fitting to a
      specific vendor layout (valid JSON + `request_id` + level +
      logger + message present).
- [ ] `RequestIdFilter` is registered BEFORE Spring Security
      (`SecurityProperties.DEFAULT_FILTER_ORDER - 10`), so 401
      responses and auth-entry-point audits carry the correlation
      id. Verified by registration test.
- [ ] `MdcUserIdFilter` is registered AFTER Spring Security
      (`SecurityProperties.DEFAULT_FILTER_ORDER + 5`), populating
      `user_id` from the authenticated principal. Verified by
      registration test.
- [ ] PHI-exposure review: `docs/security/phi-exposure-review-3f-1.md`
      landed.
- [ ] Runbook updated: `docs/runbooks/observability.md` describes
      env vars, log format, and correlation queries.
- [ ] Carry-forward closed: central request-ID generator (from 3C);
      proxy-aware `client_ip` extraction (from 3C).
- [ ] Existing 74/74 tests still green; new tests added for
      `RequestIdFilter`, `ProxyAwareClientIpResolver`,
      `MdcUserIdFilter`, end-to-end correlation, PHI-leakage, and
      filter registration.

#### 3.1.2 Phase 3F.2 — OpenTelemetry traces + metrics

- [ ] Spring Boot 3.4 Micrometer Observation API bridged to the
      OpenTelemetry SDK via `micrometer-tracing-bridge-otel`. HTTP
      server requests and JDBC calls are auto-instrumented — no
      custom code for those surfaces.
- [ ] `@Observed(name = "medcore.audit.write")` on
      `JdbcAuditWriter.write` — timer + span for every audit write,
      tagged `medcore.audit.action` (closed-enum action code) and
      `medcore.audit.outcome` (SUCCESS/DENIED/ERROR).
- [ ] `@Observed(name = "medcore.audit.chain.verify")` on
      `ChainVerifier.verify` — timer + span tagged
      `medcore.audit.chain.outcome` (`clean` / `broken` /
      `verifier_failed`).
- [ ] `ObservedAspect` bean registered so `@Observed` annotations
      are honoured (Micrometer does not auto-register the aspect).
      `org.aspectj:aspectjweaver` on classpath to satisfy the
      runtime AOP requirement.
- [ ] **Hybrid attribute filter** (`ObservationAttributeFilterConfig`)
      in place:
      - Auto-instrumented observations: deny-list strips
        `http.request.header.*`, `http.response.header.*`,
        `http.request.query.*`, `sql.parameters`,
        `db.statement.parameters`, `http.request.body`,
        `http.response.body`, `patient.*`, `user.email`,
        `user.name`, `user.display_name`.
      - Medcore-custom observations (names starting with
        `medcore.`): **allow-list** — only `medcore.audit.action`,
        `medcore.audit.outcome`, `medcore.audit.chain.outcome`, and
        standard OTel keys (`otel.*`, `error`, `exception.*`,
        `code.*`, `class`, `method`). Any other key is stripped.
      - `TracingPhiLeakageTest` asserts both at runtime.
- [ ] **KDoc guardrail** on `JdbcAuditWriter.write` names the
      prohibited span attributes (actor IDs, tenant IDs, resource
      IDs, display names, emails, reason content, row content,
      command payload) and points at the filter as the runtime
      backstop.
- [ ] OTLP exporters ship with the build but default to **unset**
      (`management.otlp.tracing.endpoint` and
      `management.otlp.metrics.export.url` both empty). Operators
      opt in by setting `MEDCORE_OTLP_TRACING_ENDPOINT` and/or
      `MEDCORE_OTLP_METRICS_URL`.
- [ ] **Startup visibility:** `ObservabilityStartupReporter` logs
      at INFO on `ApplicationReadyEvent` the state of trace export
      and metric export (endpoint or `"disabled"`). When BOTH are
      unset, logs a WARN line making clear that nothing ships.
- [ ] **`/actuator/info` signal:** `TelemetryExportInfoContributor`
      exposes `telemetry.traces.enabled`, `telemetry.traces.endpoint`,
      `telemetry.metrics.enabled`, `telemetry.metrics.endpoint`, and
      `telemetry.traces.samplingProbability` under the `info`
      actuator response.
- [ ] **Sampling default 0.1** (prod-safe) in `application.yaml`.
      Dev overrides to 1.0 via
      `MEDCORE_TRACING_SAMPLING_PROBABILITY=1.0`. Runbook documents
      **incident-response escalation**: operators MUST raise to 1.0
      for the duration of any active incident investigation.
- [ ] **Log-trace correlation:** every structured log line carries
      `trace_id` and `span_id` in addition to the 3F.1 fields
      (`request_id`, `user_id`, `tenant_id`). Spring Boot 3.4
      adds them automatically when micrometer-tracing is on the
      classpath; `logging.pattern.correlation` ensures they flow
      into the structured JSON. **Runbook clarifies that
      `request_id` (internal correlation) and `trace_id`
      (distributed correlation) are distinct and complementary.**
- [ ] **No Prometheus scrape endpoint** in 3F.2 (explicit
      non-goal — avoids the actuator-auth question). OTLP push is
      the exclusive export channel. Future need for Prometheus
      lands in a dedicated slice with an auth strategy.
- [ ] `ProgrammableVerifier` test harness in
      `ChainVerificationSchedulerTest` updated for the added
      `ObservationRegistry` constructor parameter (uses
      `ObservationRegistry.NOOP`).
- [ ] Tests:
  - `TracingConfigIntegrationTest` (7): context loads, Observation-
    Registry/Tracer/MeterRegistry/ObservedAspect all present,
    sampling default 0.1, OTLP endpoints unset.
  - `AuditWriteObservationTest` (1): real /api/v1/me drives ≥ 2
    audit writes; `medcore.audit.write` timer count increments;
    tag keys are allow-list subset.
  - `ChainVerifyObservationTest` (2): chain verify increments
    timer; outcome tag values in closed set.
  - `TracingPhiLeakageTest` (2): no `medcore.*` meter carries keys
    outside the allow list; no tag values match obvious PHI
    shapes (email, SSN, bearer, date).
- [ ] PHI-exposure review landed: `docs/security/phi-exposure-review-3f-2.md`.
- [ ] Runbook updated: `docs/runbooks/observability.md` gains the
      "OpenTelemetry traces and metrics (Phase 3F.2)" section
      with: auto-instrumentation scope, custom observation names,
      sampling defaults + incident escalation, OTLP configuration,
      request_id vs trace_id clarification, startup-log format,
      `/actuator/info` telemetry block shape.
- [ ] Phase 3F closes: all four sub-slices (3F.1 + 3F.2 + 3F.3 +
      3F.4) are complete.
- [ ] Existing 155/155 tests still green; +12 new tests (7 + 1 +
      2 + 2) across 4 new suites. Total: 167/167 across 39 suites.

#### 3.1.3 Phase 3F.3 — Health and readiness probes

- [ ] Spring Boot Actuator dependency added
      (`org.springframework.boot:spring-boot-starter-actuator`); BOM-
      managed version; recorded in the 3F.3 slice commit.
- [ ] `/actuator/health/liveness` returns 200 `{"status":"UP"}`
      anonymously. Does NOT depend on the datasource — restarting
      the app cannot fix a DB outage, so the probe must not tie
      liveness to DB reachability.
- [ ] `/actuator/health/readiness` returns 200 `{"status":"UP"}`
      anonymously AND depends on the **runtime datasource path**
      (`medcore_app` role `SELECT 1`). A passing readiness probe
      proves the app's request-path is healthy, not merely that the
      JVM is up.
- [ ] `/actuator/health` aggregate returns 200 anonymously AND is
      **detail-free**: response body is exactly `{"status":"..."}`
      — no `components`, no `details`, no indicator names, no DB
      version, no dependency graph. Enforced by
      `management.endpoint.health.show-details: never` and asserted
      by `ActuatorProbesIntegrationTest`.
- [ ] `/actuator/info` returns 200 anonymously with JSON content
      type.
- [ ] All four exposed endpoints return an `application/*+json` or
      `application/json` content type.
- [ ] Non-exposed actuator endpoints return **404** (NOT 401, NOT
      200): `/actuator/env`, `/actuator/metrics`,
      `/actuator/prometheus`, `/actuator/beans`,
      `/actuator/mappings`. The 404 behaviour is the MVC-level
      non-exposure contract; the security chain is belt, MVC
      exposure is braces.
- [ ] `/api/**` still requires authentication (anonymous → 401).
      Regression assertion in the actuator test suite.
- [ ] Dedicated `ActuatorSecurityConfig` SecurityFilterChain at
      `@Order(1)` with `securityMatcher("/actuator/**")`, permitting
      anonymous access only to `/actuator/health`, `/actuator/health/**`,
      and `/actuator/info`; everything else under the chain defaults
      to `authenticated()`.
- [ ] Runbook `docs/runbooks/observability.md` updated with the
      Actuator-probes section, Kubernetes / ECS probe-config
      examples, and the explicit statement that readiness validates
      the `medcore_app` runtime datasource path (not just JVM
      liveness).
- [ ] Existing 109/109 tests still green + new
      `ActuatorProbesIntegrationTest` cases added (liveness,
      readiness, aggregate-detail-free, info, env→404, metrics→404,
      prometheus→404, beans→404, mappings→404, `/api/**`→401).
- [ ] `RequestIdFilter` (from 3F.1) continues to run on
      `/actuator/**` paths so probe logs carry `request_id` for
      debugging.
- [ ] Non-goals honoured: no Prometheus (3F.2), no OpenTelemetry
      (3F.2), no scheduled audit job (3F.4), no build-info plugin
      (separate slice), no custom HealthIndicator.

#### 3.1.4 Phase 3F.4 — Audit chain verification scheduled job

- [ ] Spring `@Scheduled` job invokes `audit.verify_chain()` (V9,
      Phase 3D) on a configurable cadence. Default cron:
      `0 0 * * * *` (top of every hour). First execution fires at
      the next matching cron time — `@Scheduled` does not support a
      separate initial-delay for cron triggers; dev shortens the
      cron via env var for faster feedback.
- [ ] **Read-only contract** (asserted by
      `ChainVerifierReadOnlyTest`): verification performs no INSERT,
      no UPDATE, no DELETE on `audit.audit_event`. Row count,
      sequence numbers, and row-hashes are byte-identical before
      and after. Any future edit that breaks this MUST be caught in
      review.
- [ ] **Clean outcome:** no audit event emitted. Log at DEBUG
      (silent under default production logging). Dev overrides
      `MEDCORE_AUDIT_CHAIN_VERIFICATION_VERBOSE_CLEAN_LOG=true` to
      see the line at INFO.
- [ ] **Broken outcome:** exactly ONE `audit.chain.integrity_failed`
      audit event per cycle (not per break row — a 1000-row broken
      chain produces ONE event, not 1000). Log at ERROR. Reason
      slug format: `breaks:<N>|reason:<first-code>` where
      `<first-code>` is one of the closed V9 reason codes
      (`sequence_no_null`, `sequence_gap`,
      `first_row_has_prev_hash`, `prev_hash_mismatch`,
      `row_hash_mismatch`). No row content, no tenant id, no
      actor id.
- [ ] **Verifier-failed outcome** (infra failure, NOT chain
      breakage): exactly ONE `audit.chain.verification_failed`
      audit event per cycle with reason slug `verifier_failed`.
      Log at ERROR. Distinct from `integrity_failed` so a
      compliance reviewer can distinguish "chain broken" (clinical
      safety incident) from "we could not check the chain" (infra
      incident).
- [ ] **Concurrency guard:** `AtomicBoolean.compareAndSet` prevents
      overlapping executions if a run takes longer than the cron
      interval. Overlapping tick is skipped (logged at DEBUG, no
      audit). Scope: single-instance. Multi-instance deployment
      will replace this with a distributed lock (ShedLock or
      equivalent) via ADR when Phase 3I's deployment baseline is
      multi-instance.
- [ ] **Failure isolation:** a bug in the scheduler catches any
      `Throwable` at the outermost layer and logs ERROR without
      re-throwing. A failed verification does NOT fail application
      startup, readiness, or any other request. Deliberate
      departure from ADR-003 §2's "failed audit fails the audited
      action" — verification is a background observability task,
      not a user-facing transaction.
- [ ] **No auto-remediation.** The verifier never calls
      `audit.rebuild_chain()`. Remediation is an operator concern
      via the migrator role, never the app.
- [ ] **No HTTP operator surface.** Manual verification /
      chain-status endpoint is deferred to Phase 3J alongside
      tenancy writes and RBAC.
- [ ] **Environment-aware activation:** default `enabled=true`.
      Tests disable by default via `application-test` override; the
      dedicated `ChainVerificationSchedulerTest` opts in via
      `@TestPropertySource` and drives `runVerification()` directly
      to bypass cron timing.
- [ ] `AuditAction.AUDIT_CHAIN_INTEGRITY_FAILED` and
      `AuditAction.AUDIT_CHAIN_VERIFICATION_FAILED` added.
      `AuditAction` KDoc carries the ADR-005-style registry
      discipline note. Adding a future audit action requires the
      same rigor (schema impact review, at-least-one test,
      review-pack callout).
- [ ] `@EnableScheduling` scoped to a dedicated
      `ChainVerificationScheduleConfig` rather than hoisted to a
      global platform config (premature hoisting creates a shared
      concern every future consumer inherits). Hoisting lands when
      three or more slices demonstrate a consistent need, via ADR.
- [ ] Tests:
  - `ChainVerifierTest` — real DB; clean chain returns `Clean`
    (both empty and populated cases), tampered row returns
    `Broken` with count ≥ 1 and a known V9 reason code.
  - `ChainVerificationSchedulerTest` — `@TestConfiguration`
    substitutes a programmable verifier; asserts all four dispatch
    paths: clean silent, broken emits ONE `integrity_failed`,
    verifier-failed emits ONE `verification_failed`, and repeated
    invocations emit one event per cycle (not per call).
  - `ChainVerifierReadOnlyTest` — byte-for-byte assertion that
    `audit.audit_event` is unchanged after verification.
- [ ] `docs/runbooks/observability.md` updated with the chain
      verification section, including the clean/broken/failed
      behaviour table, env-var overrides, and incident response
      guidance (manual `rebuild_chain()` is migrator-role only,
      never the app).
- [ ] `docs/security/phi-exposure-review-3f-4.md` landed.
- [ ] Carry-forward closed: "chain verification scheduled job"
      (3D → 3F.4). Operator surface remains deferred to 3J.
- [ ] Existing 147/147 tests still green; +8 new tests (3 + 4 + 1)
      across 3 new suites.

### 3.2 Phase 3G — Error standardization

- [ ] Every 4xx / 5xx response from the backend uses the shared
      `ErrorResponse` envelope (`code`, `message`, `requestId`,
      `details`). Wire shape unchanged from 3B.1; only population
      rules tightened.
- [ ] Every error response populates `requestId` from MDC
      (`MdcKeys.REQUEST_ID`, Phase 3F.1 substrate). Body `requestId`
      MUST equal the response's `X-Request-Id` header —
      `HeaderBodyRequestIdParityTest` asserts across every status
      class.
- [ ] **401 Unauthorized.** Unified envelope with code
      `auth.unauthenticated` and stable message `"Authentication
      required."` regardless of cause. `WWW-Authenticate: Bearer`
      header preserved (RFC 6750 §3). Emitted by
      `MedcoreAuthenticationEntryPoint` (new in 3G) wrapped by the
      existing `AuditingAuthenticationEntryPoint`. Audit emission of
      `identity.user.login.failure` behaviour is unchanged.
- [ ] **403 Forbidden.** Two codes, one message. `auth.forbidden`
      (emitted by `MedcoreAccessDeniedHandler` for filter-chain
      denials and by `GlobalExceptionHandler.onAccessDenied` for
      method-security denials) and `tenancy.forbidden` (emitted by
      `TenantContextFilter` and `TenancyExceptionHandler`). Both
      MUST use the IDENTICAL message `"Access denied."` — "tenant"
      never appears in any 403 message body (Rule 01 §enumeration).
- [ ] **404 Not Found.** Code `resource.not_found`. Handlers:
      `NoResourceFoundException` (no-such-route),
      `EntityNotFoundException` (JPA), `EmptyResultDataAccessException`.
- [ ] **409 Conflict.** Code `resource.conflict`. Handlers:
      `OptimisticLockingFailureException`,
      `ObjectOptimisticLockingFailureException`, and
      `DataIntegrityViolationException` IFF the underlying PostgreSQL
      SQLSTATE is `23505` (unique), `23503` (FK), or `23P01`
      (exclusion). Check-constraint and NOT-NULL violations DO NOT
      map to 409.
- [ ] **422 Unprocessable Entity.** Two codes.
      `request.validation_failed` for `MethodArgumentNotValidException`,
      `HandlerMethodValidationException`, `ConstraintViolationException`,
      AND for `DataIntegrityViolationException` with SQLSTATE `23502`
      (not-null) or `23514` (check). `tenancy.context.required` for
      `TenantContextMissingException`. Validation payload carries
      `details.validationErrors = [{field, code}]` — field names only,
      never field values.
- [ ] **500 Internal Server Error.** Code `server.error`. Fallback
      for any uncaught `Throwable` and for
      `DataIntegrityViolationException` with unrecognised SQLSTATE.
      Response body carries zero information about the cause — full
      detail is logged, correlation is exclusively via `requestId`.
- [ ] **400 Bad Request is EXPLICITLY NOT normalised in Phase 3G.**
      Spring Boot's default handling for
      `HttpMessageNotReadableException` (malformed JSON),
      `MissingServletRequestParameterException`, type-mismatch etc.
      remains framework-default. Tracked as carry-forward for a
      future slice.
- [ ] **Precedence rule documented in code and enforced by
      `@Order`:** module-specific `@RestControllerAdvice` classes
      (e.g., `TenancyExceptionHandler`) carry
      `@Order(Ordered.HIGHEST_PRECEDENCE)` → `GlobalExceptionHandler`
      carries `@Order(Ordered.LOWEST_PRECEDENCE)` → its `Throwable`
      fallback within. Module advisers always win over the global
      one when both would match.
- [ ] **Leakage discipline tested.** `ErrorResponsePhiLeakageTest`
      asserts no response body contains stack traces, framework
      exception class names, SQL fragments, bearer-token values, or
      the deliberately-seeded "suspicious" exception text from the
      test-only controller.
- [ ] **Audit-on-403-access-denied is deliberately deferred to 3J.**
      Current slice has no RBAC callers of `@PreAuthorize`, so any
      audit here would be noise without attribution.
- [ ] **Test-only `ErrorPathsTestController` gated by
      `@ConditionalOnProperty(value = "medcore.testing.error-paths-controller.enabled",
      havingValue = "true", matchIfMissing = false)`** — mounts only
      when explicitly enabled in a test's `@TestPropertySource`.
      Lives under `src/test/kotlin` so it cannot ship to production
      regardless.
- [ ] Security config gains `@EnableMethodSecurity` so `@PreAuthorize`
      annotations (starting with the denyAll test endpoint) are
      enforced.
- [ ] Existing 119/119 tests still green + new tests added
      (`GlobalExceptionHandlerIntegrationTest`,
      `ErrorResponsePhiLeakageTest`, `HeaderBodyRequestIdParityTest`).
- [ ] PHI-exposure review landed: `docs/security/phi-exposure-review-3g.md`.
- [ ] Runbook updated: `docs/runbooks/observability.md` notes the
      `requestId` correlation from error body to log line.
- [ ] Carry-forward closed: uniform 401 envelope (from 3B.1);
      `TenantContextMissingException` HTTP mapping (from 3B.1).

### 3.3 Phase 3H — Secrets + production posture

- [ ] Out-of-process Flyway for production deployments:
  - Gradle Flyway plugin (`org.flywaydb.flyway` 10.20.1) wired
    in `apps/api/build.gradle.kts` for dev / CI.
  - `docker/flyway-migrator/Dockerfile` builds on
    `flyway/flyway:10.20.1` for production.
  - Shared `apps/api/flyway.conf` is the single source of truth;
    both paths consume it; no secrets in the file.
  - `spring.flyway.enabled` defaults true (dev/test); prod sets
    `MEDCORE_APP_RUN_MIGRATIONS=false`.
- [ ] `SecretSource` abstraction (3 implementations):
  `EnvVarSecretSource` (via Spring Environment — relaxed binding
  handles env vars + system props), `StaticSecretSource` (test-
  only), `AwsSecretsManagerSecretSource` (stub throwing with
  `TODO(3I)`).
- [ ] Fail-fast contract: `SecretSource.get()` throws
  `IllegalStateException` with a diagnostic naming the key AND the
  source on missing/blank values. Tested.
- [ ] `SecretValidator` `@PostConstruct` probes every
  `REQUIRED_SECRETS` entry through `SecretSource.get()`. Missing
  any secret aborts context refresh BEFORE
  `ApplicationStartedEvent` — readiness stays DOWN.
- [ ] `FlywayMigrationStateCheck` active when
  `spring.flyway.enabled=false` (prod). Queries
  `flyway.flyway_schema_history.installed_rank` and aborts boot
  when below `MIN_EXPECTED_INSTALLED_RANK` (compile-time constant
  at 11 after V11).
- [ ] `JpaDependsOnFlywayCheck` wires EntityManagerFactory to
  depend on the check — Hibernate's `ddl-auto=validate` cannot
  race ahead of stale-schema detection.
- [ ] `V11__medcore_migrator_role.sql`:
  - Creates `medcore_migrator` role idempotently with LOGIN
    NOINHERIT **NOBYPASSRLS** NOCREATEDB NOCREATEROLE NOSUPERUSER
    NOREPLICATION.
  - GRANTs USAGE + CREATE on `flyway`, `identity`, `tenancy`,
    `audit` schemas.
  - GRANTs ALL on `flyway` schema tables (owns `flyway_schema_history`).
  - REVOKEs ALL ON ALL TABLES IN SCHEMA identity/tenancy/audit —
    migrator does DDL, not data reads.
  - GRANTs medcore_app USAGE on flyway schema + SELECT on
    flyway_schema_history (for FlywayMigrationStateCheck).
  - Password NOT set — ops concern.
  - `MigratorRoleIntegrationTest` asserts all of the above at
    runtime.
- [ ] `MedcoreAppPasswordSync` (from 3E) marked `@Deprecated`.
  VERIFY path removed (SecretValidator handles it). SYNC path
  retained for local-dev ergonomics ONLY, gated by:
  - `medcore.db.app.passwordSyncEnabled=true` (existing opt-in), AND
  - production-issuer guard: OIDC issuer must contain
    `localhost`, `127.0.0.1`, `[::1]`, or `mock-oauth2-server`.
  Refuses with an ADR-006-citing error otherwise.
- [ ] `JpaDependsOnPasswordCheck` gated by
  `spring.flyway.enabled=true` (via `@ConditionalOnProperty`)
  so production deployments (Flyway disabled) don't fail bean
  resolution on the missing `medcoreAppPasswordSync` bean.
- [ ] ADR-006 lands with the four decisions locked: out-of-
  process Flyway, SecretSource, medcore_migrator least privilege,
  migration-before-app invariant.
- [ ] `docs/runbooks/secrets-and-migrations.md` is a complete
  operator runbook:
  - Secrets inventory table.
  - Deployment sequence (Kubernetes initContainer + ECS pre-deploy
    task examples).
  - Flyway migrator container build + run instructions.
  - Password rotation procedure for `medcore_app` AND
    `medcore_migrator` with a standardised evidence template.
  - Failure playbook for `REFUSING TO START: expected Flyway
    state...`.
  - Failure playbook for `REFUSING TO START: required secret(s)
    missing...`.
  - AWS Secrets Manager (Phase 3I) forward-look section.
- [ ] `docs/security/phi-exposure-review-3h.md` landed.
  Risk: None.
- [ ] `MedcoreApiApplicationTests.flyway history records expected
  migrations in order` updated for V11.
- [ ] Tests:
  - `SecretSourceTest` (9): EnvVar get/getOrNull missing/blank/present;
    Static get/throw; AWS stub always throws; AWS not `@Component`.
  - `SecretValidatorTest` (4): validate passes / fails / names
    key / runbook pointer.
  - `MedcoreAppPasswordSyncTest` (5): sync disabled no-op; issuer
    guard refuses non-local; local issuer passes; mock issuer
    passes; blank issuer passes.
  - `MigratorRoleIntegrationTest` (7): CAN create; CANNOT
    select identity/tenancy/audit; NOBYPASSRLS; NOSUPERUSER; CAN
    read flyway_schema_history.
- [ ] Existing 168 tests still green; total after 3H: 191/191
  across 42 suites (+23).
- [ ] Three 3E carry-forwards close: Flyway out-of-process (3E→3H);
  password rotation flow (3E→3H); medcore_migrator distinct role
  (3E→3H).
- [ ] One carry-forward opens to 3I: real AWS Secrets Manager
  implementation + MedcoreAppPasswordSync deletion.

### 3.4 Phase 3J — WriteGate + tenancy writes + RBAC

Phase 3J is divided into a substrate slice (3J.1) and one or more
endpoint slices (3J.2+). The substrate slice establishes the
mutation framework, authority model, and DB safety net; endpoint
slices wire concrete tenancy commands through it. The phase itself
closes when all declared endpoint slices have landed AND every
3J-scope carry-forward is reconciled.

#### 3.4.1 Phase 3J.1 — WriteGate substrate + authority model + RLS writes

- [ ] `WriteGate<CMD, R>` executes the pipeline
      `validate → authorize → transact-open → apply → audit-success →
      transact-close` in order. `WriteGateTest.apply-runs-validator-
      then-policy-then-execute-then-audit-success-in-order` asserts
      the ordering contract.
- [ ] **WriteGate owns the transaction boundary.** The gate injects
      `PlatformTransactionManager` and runs `apply + audit-success`
      inside `TransactionTemplate.execute { ... }`. Callers MUST
      NOT rely on enclosing `@Transactional`. Rationale and
      invariant locked in ADR-007 §2.2 and §4.2.
- [ ] **Denial path:** `WriteAuthorizationException` thrown by the
      policy fires `WriteAuditor.onDenied` with a closed-enum
      `WriteDenialReason`, logs nothing at the gate, and re-throws.
      Denial path does NOT open a transaction.
      `WriteGateTest.authz-denial-emits-onDenied-audit-and-re-
      throws-without-running-execute` asserts the contract.
- [ ] **Denial-audit failure preserves the original denial.** If
      `onDenied` throws, the gate logs ERROR and re-throws the
      ORIGINAL `WriteAuthorizationException`. Asserted by
      `WriteGateTest.denial-audit-throwing-does-not-swallow-the-
      original-denial`.
- [ ] **Execute / success-audit failure semantics.** Execute
      exception propagates and `onSuccess` is never called; the
      `apply + audit-success` atomic pair rolls back together when
      either throws. Both asserted by `WriteGateTest`.
- [ ] `WriteContext(principal: MedcorePrincipal, idempotencyKey:
      String? = null)` defined. Idempotency slot is **shape-only in
      3J.1** — framework does not read, dedupe, or log the value.
      PHI review will re-examine when dedupe logic lands.
- [ ] **Audit intent via `reason` field.** `WriteAuditor.onSuccess`
      populates the existing `audit.audit_event.reason` column
      with `intent:<command-class-slug>` so coarse `action` +
      fine-grain intent together distinguish sibling mutations. No
      schema change.
- [ ] **Validator-less gate skips the validate step** without
      breaking ordering. Asserted by `WriteGateTest.validator-
      less-gate-skips-validation-step-and-still-enforces-order`.
- [ ] `WriteResponse<T>(data, requestId)` uniform success envelope
      defined. Not yet consumed by any endpoint; first use lands
      in 3J.2.
- [ ] `MedcoreAuthority` closed enum implements
      `GrantedAuthority`. Seven entries: `TENANT_READ`,
      `TENANT_UPDATE`, `TENANT_DELETE`, `MEMBERSHIP_READ`,
      `MEMBERSHIP_INVITE`, `MEMBERSHIP_REMOVE`, `SYSTEM_WRITE`.
      Splitting `MANAGE` pre-emptively (ADR-007 §2.5): renaming a
      shipped authority is a breaking security-contract change.
- [ ] **Role → authority map locked** in `MembershipRoleAuthorities`:
      `OWNER` = every tenancy authority INCLUDING `TENANT_DELETE`;
      `ADMIN` = every tenancy authority EXCEPT `TENANT_DELETE`;
      `MEMBER` = `TENANT_READ` + `MEMBERSHIP_READ` only.
      `SYSTEM_WRITE` is NEVER in any standard role mapping.
      Asserted by `MembershipRoleAuthoritiesTest` (4 tests).
- [ ] **Registry discipline for `MedcoreAuthority`** (ADR-007 §2.5,
      ADR-005 §2.3 pattern). Adding: enum entry + map update +
      `MembershipRoleAuthoritiesTest` update + review-pack callout.
      Renaming / removing: superseding ADR.
- [ ] `AuthorityResolver.resolveFor(userId, tenantSlug)` issues
      ONE read-only SELECT against `tenancy.tenant` +
      `tenancy.tenant_membership`; returns an empty set for
      non-member, suspended-membership, suspended-tenant, and
      unknown-slug cases. No caching in 3J.1 (deferred behind ADR
      if performance warrants). Seven cases covered by
      `AuthorityResolverIntegrationTest`.
- [ ] `V12__tenancy_rls_write_policies.sql` lands with:
  - `p_tenant_insert_none` — direct INSERT on `tenancy.tenant`
    denied for `medcore_app` (`WITH CHECK (false)`). Only
    `tenancy.bootstrap_create_tenant` can insert.
  - `p_tenant_update_by_admin_or_owner` — USING + WITH CHECK
    require ACTIVE OWNER/ADMIN membership in the target tenant.
  - `p_tenant_delete_by_owner` — USING requires ACTIVE OWNER.
  - `p_membership_{insert,update,delete}_by_admin_or_owner` —
    parallel policies on `tenancy.tenant_membership`.
  - `GRANT SELECT, INSERT, UPDATE, DELETE ON tenancy.tenant /
    tenancy.tenant_membership TO medcore_app` — role-level grant
    with RLS above constraining reach.
  - `tenancy.bootstrap_create_tenant(TEXT, TEXT, UUID) RETURNS
    UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path =
    tenancy, pg_temp` — owner `medcore_migrator`. Body INSERTs
    into `tenancy.tenant` + `tenancy.tenant_membership`.
    Arguments bound as parameters; no string interpolation.
  - `REVOKE ALL ON FUNCTION ... FROM PUBLIC;` +
    `GRANT EXECUTE ... TO medcore_migrator;` — app role cannot
    invoke. Asserted at runtime.
- [ ] `TenancyRlsWriteTest` (7 tests) proves RLS is the real
      envelope: cross-tenant UPDATE refused; cross-tenant DELETE
      refused; cross-tenant membership INSERT refused; direct
      INSERT on `tenancy.tenant` refused (routes through bootstrap
      only); OWNER/ADMIN of own tenant can UPDATE; MEMBER role
      cannot UPDATE; `bootstrap_create_tenant` not callable as
      `medcore_app`.
- [ ] `AuditAction.AUTHZ_WRITE_DENIED("authz.write.denied")` added
      to the closed-enum registry. Registry discipline (ADR-005
      §2.3) preserved.
- [ ] `FlywayMigrationStateCheck.MIN_EXPECTED_INSTALLED_RANK`
      bumped to 12.
- [ ] `MedcoreApiApplicationTests.flyway history records expected
      migrations in order` updated for V12.
- [ ] ADR-007 accepted: WriteGate mutation architecture + 3J / 3I
      reorder. Amends `02-roadmap.md` phase order — Tier 3 per
      ADR-005 §2.3.
- [ ] `docs/security/phi-exposure-review-3j.md` landed. Risk:
      None (authz plumbing; no PHI path introduced).
- [ ] Tests:
  - `WriteGateTest` (7): ordering; validator short-circuit;
    denial emits audit and re-throws; denial-audit failure
    preserves denial; execute-throws skips success audit;
    success-audit-throws propagates; validator-less gate.
  - `MembershipRoleAuthoritiesTest` (4): OWNER full set; ADMIN
    no-DELETE; MEMBER read-only; OWNER is strict superset of
    ADMIN.
  - `AuthorityResolverIntegrationTest` (7): active OWNER/ADMIN/
    MEMBER; suspended membership empty; suspended tenant empty;
    non-member empty; unknown slug empty.
  - `TenancyRlsWriteTest` (7): RLS envelope as above.
- [ ] Existing 193 tests still green; total after 3J.1: **218/218
      across 47 suites (+25).**
- [ ] Carry-forward closed in 3J.1: RLS policies for tenancy
      writes (3D → 3J via V12); role → authority mapping (3A.3 →
      3J via `MembershipRoleAuthorities` + `AuthorityResolver`).
- [ ] Carry-forward opens to 3J.2+: first concrete endpoint
      (`PATCH /api/v1/tenants/{slug}` for display_name) through
      the framework; `POST /api/v1/tenants`; membership
      invite/remove/role-update; chain verification operator
      surface (carried from 3F.4 per 3F.4 non-goals and the
      carry-forward ledger).

#### 3.4.2 Phase 3J.2 — First concrete endpoint through WriteGate

The first concrete mutation command through the 3J.1 framework.
`PATCH /api/v1/tenants/{slug}` updates `displayName` only —
status, slug, and deletion remain out of scope for subsequent
slices.

- [ ] `PATCH /api/v1/tenants/{slug}` implemented on
      `TenantsController`; body `{displayName: String}`; success
      returns `200 OK` with `WriteResponse<TenantSummaryResponse>`.
- [ ] Command / policy / validator / handler / auditor / config
      stack in `com.medcore.tenancy.write`:
      `UpdateTenantDisplayNameCommand`, `Validator`, `Policy`,
      `Handler`, `Auditor`, `TenantWriteConfig`, `TenantSnapshot`.
      **Every future tenancy write follows this exact layout.**
- [ ] `UpdateTenantRequest` DTO in `com.medcore.tenancy.api`;
      `displayName: String?` + `@NotBlank` + `@Size(max = 200)`.
      Nullable type deliberate — missing field trips `@NotBlank`
      → 422, not the Phase 3G-deferred 400 path.
- [ ] `TenantSummaryResponse.from(snapshot)` factory added;
      write-path + read-path return identical outbound wire shape.
- [ ] Controller trims `displayName` before command construction;
      validator enforces post-trim emptiness + ISO-control-
      character rejection + length ≤ 200 + slug format
      (`^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$`).
- [ ] Policy requires `MedcoreAuthority.TENANT_UPDATE` (OWNER +
      ADMIN; MEMBER denied). Denial reasons forwarded from the
      new sealed `AuthorityResolution.Denied(reason)` so the
      `AUTHZ_WRITE_DENIED` audit row carries the specific
      `denial:<code>` slug, not the coarse "empty set."
- [ ] **`AuthorityResolver` refactored to return sealed
      `AuthorityResolution.Granted(authorities) | Denied(reason)`**
      in place of `Set<MedcoreAuthority>`. Phase 3J.1 tests
      rewritten to assert the Denied variant by reason. Known
      limitation: `MEMBERSHIP_SUSPENDED` collapses to
      `NOT_A_MEMBER` due to V8 RLS on `tenancy.tenant` hiding
      the tenant row from suspended members. Documented in the
      resolver KDoc and ADR-007 §7.1; carry-forward to a future
      V13+ SECURITY DEFINER resolution function.
- [ ] **`WriteTxHook` seam added to `WriteGate`** with
      `TenancyRlsTxHook` wired as the default for tenancy writes.
      Sets `app.current_user_id` GUC inside the gate's tx so
      RLS-protected handler reads succeed. Without this,
      `@Valid`-and-policy-passed writes returned 404 because RLS
      filtered the handler's `findBySlug` to zero rows (the GUC
      was set only in the policy's short-lived read tx).
- [ ] **`WriteValidationException(field, code)`** added in
      `platform/write/` + one `@ExceptionHandler` on
      `GlobalExceptionHandler` mapping to 422 with the
      `details.validationErrors = [{field, code}]` shape the
      bean-validation path already uses.
- [ ] **`AuditAction.TENANCY_TENANT_UPDATED`** registry entry
      (`tenancy.tenant.updated`). Registry discipline applied —
      test emission, KDoc, runbook mention (in the next runbook
      revision slice; not blocking 3J.2).
- [ ] **Handler no-op optimisation:** if
      `entity.displayName == command.displayName`, return snapshot
      with `changed = false` and skip `.save()`. Auditor
      suppresses emission when `changed = false`. Preserves
      "every persisted change emits an audit row" (a no-op
      persists no change).
- [ ] Audit success row: `action=tenancy.tenant.updated`,
      `reason=intent:tenant.update_display_name`,
      `resource_type=tenant`, `resource_id=<slug>`,
      `tenant_id=<uuid>`, `outcome=SUCCESS`. No before/after
      state — audit payload slice deferred to Phase 7 compliance
      requirement (tracked as ADR-007 §7.1 carry-forward).
- [ ] Audit denial row: `action=authz.write.denied`,
      `reason=intent:tenant.update_display_name|denial:<code>`,
      `resource_type=tenant`, `resource_id=<slug>`,
      `tenant_id=null`, `outcome=DENIED`. Slug captured even on
      unknown-tenant path for enumeration-attempt forensics.
- [ ] Unknown slug returns 403 (NOT 404) — hides tenant
      existence from non-members; enumeration protection.
- [ ] No `If-Match` header requirement (3J.2 accepts
      last-writer-wins). JPA `@Version` bumps on flush;
      concurrent writes produce 409 via
      `OptimisticLockingFailureException` → 3G envelope.
      `If-Match` carry-forward to a future slice when a client
      demands it.
- [ ] `Idempotency-Key` header accepted but shape-only (3J.1
      `WriteContext.idempotencyKey`). Not read, not deduped, not
      logged. Dedupe semantics carry-forward to Phase 4A.
- [ ] Response body `requestId` equals response header
      `X-Request-Id` — asserted in integration test.
- [ ] **ADR-007 addendum §2.10** — "WriteGate is the exclusive
      mutation entry point." Review-gated until Phase 3I's
      ArchUnit enforcement.
- [ ] **ADR-007 addendum §2.11** — "`WriteTxHook` — caller-
      dependent tx-local state." Framework-level seam documented.
- [ ] `docs/security/phi-exposure-review-3j-2.md` landed.
      Risk: None (tenant metadata; no clinical path).
- [ ] Tests:
  - `UpdateTenantDisplayNameValidatorTest` (6): happy path;
    blank; whitespace-only; overlong (201); control-char;
    malformed slug.
  - `UpdateTenantDisplayNameIntegrationTest` (15): 401 unauth;
    OWNER 200 + audit; ADMIN 200; MEMBER 403 +
    denial=insufficient_authority; non-member 403 +
    denial=not_a_member; suspended membership 403 +
    denial=not_a_member (RLS-collapsed); tenant-suspended 403 +
    denial=tenant_suspended; unknown-slug 403 +
    denial=not_a_member; empty 422; whitespace-only 422;
    overlong 422; missing-field 422; no-op 200 NO audit row
    row_version unchanged; second identical PATCH one audit row;
    requestId parity; Idempotency-Key passthrough.
  - `WriteGateTest` (+2): txHook runs inside tx after authz
    before execute; txHook does NOT run on denial path.
  - `AuthorityResolverIntegrationTest` (7) rewritten: assert
    specific `Granted(authorities)` or `Denied(reason)` for each
    case; MEMBERSHIP_SUSPENDED case asserts the RLS-collapse into
    NOT_A_MEMBER with a comment pointing at the remediation path.
- [ ] Existing 218 tests still green; total after 3J.2: **242/242
      across 49 suites (+24).**
- [ ] Carry-forward closed in 3J.2:
  - `WriteResponse` envelope stability for first consumer
    (still `data + requestId` only; typed-extensions deferred
    until first caller needs them — ADR-007 §7.1).
  - First concrete tenancy write endpoint (tracked from 3J.1).
- [ ] Carry-forward opened from 3J.2:
  - `MEMBERSHIP_ROLE_UPDATE` authority (first role-change
    endpoint, 3J.3+).
  - V13+ SECURITY DEFINER `tenancy.resolve_authority(slug, uid)`
    function to close the RLS-induced `MEMBERSHIP_SUSPENDED`
    collapse.
  - Audit payload column / structured diff for mutation
    before/after reconstruction — future ADR, driven by
    compliance need (realistic trigger Phase 7).
  - `If-Match` precondition header on PATCH — when a UI or
    integration client demands it.
  - ArchUnit rule enforcing "WriteGate is exclusive mutation
    entry point" — Phase 3I.
  - `PhiRlsTxHook` sibling that additionally sets
    `app.current_tenant_id` — Phase 4A.

#### 3.4.3 Phase 3J.3 — Membership invite through WriteGate

Second concrete command through the 3J.1 framework. Expands the
RBAC surface to real multi-user workflows:
`POST /api/v1/tenants/{slug}/memberships` creates an ACTIVE
membership for an already-provisioned user. Email-token
invitation flow (PENDING lifecycle) remains deferred.

- [ ] `POST /api/v1/tenants/{slug}/memberships` implemented on
      `TenantsController`; body `{userId, role}`; success returns
      `201 Created` with `WriteResponse<MembershipResponse>`.
- [ ] Command / policy / validator / handler / auditor / snapshot
      stack in `com.medcore.tenancy.write`. Pattern identical to
      3J.2's UpdateTenantDisplayName stack — second
      instantiation of the reusable layout.
- [ ] `InviteMembershipRequest` DTO in `com.medcore.tenancy.api`;
      `userId: UUID?` + `role: MembershipRole?` both `@NotNull`.
      Missing fields land on 422 via the 3G envelope.
- [ ] `MembershipResponse.from(snapshot)` factory added;
      post-write body shape matches the existing read-side
      `MembershipResponse`.
- [ ] Policy enforces `MedcoreAuthority.MEMBERSHIP_INVITE`
      (OWNER + ADMIN) + **ADR-007 §4.9 privilege-escalation
      guard**: `role == OWNER` additionally requires the
      caller hold `MedcoreAuthority.TENANT_DELETE`
      (OWNER-only authority). ADMIN trying to invite OWNER
      fires `denial:insufficient_authority` — identical wire
      signal to "MEMBER tried to invite," distinguishable
      only through the recorded actor role in
      `tenant_membership` at the audited timestamp.
- [ ] **Target user existence** verified in the handler via
      `identityUserRepository.existsById`. Missing user
      surfaces as 422 `{field:"userId", code:"user_not_found"}`
      via `WriteValidationException`, NOT 404. Avoids leaking
      user-existence to authenticated admins.
- [ ] **No app-layer duplicate-check.** The V6
      `uq_tenancy_membership_tenant_user UNIQUE (tenant_id,
      user_id)` constraint is the authoritative guard — race-safe
      under concurrent writes. SQLSTATE 23505 → 409
      `resource.conflict` via the existing 3G mapping.
- [ ] `AuditAction.TENANCY_MEMBERSHIP_INVITED` registry entry
      (`tenancy.membership.invited`). Registry discipline
      (ADR-005 §2.3, ADR-007 §2.5) applied: enum entry + test
      emission + KDoc + review-pack callout.
- [ ] **Structured target-user capture on denial audit** (§9.4
      pressure-test decision, ADR-007 §4.9). Denial row uses
      `resource_type = "tenant_membership"`,
      `resource_id = <target user UUID>` — the only column-level
      structured identifier available when `tenant_id` is null
      (enumeration protection). Compliance queries are
      column-based, not `reason` string-parsed.
- [ ] Audit success row: `action=tenancy.membership.invited`,
      `reason=intent:tenancy.membership.invite`,
      `resource_type=tenant_membership`,
      `resource_id=<new membership UUID>`,
      `tenant_id=<tenant UUID>`, `outcome=SUCCESS`.
- [ ] Audit denial row: `action=authz.write.denied`,
      `reason=intent:tenancy.membership.invite|denial:<code>`,
      `resource_type=tenant_membership`,
      `resource_id=<target user UUID>`, `tenant_id=null`,
      `outcome=DENIED`.
- [ ] Unknown slug returns 403 + `denial:not_a_member`
      (enumeration protection inherited from 3J.2).
- [ ] Suspended tenant returns 403 + `denial:tenant_suspended`
      even for an OWNER (TENANT_SUSPENDED resolves correctly
      in the sealed `AuthorityResolution.Denied` path).
- [ ] Suspended membership collapses to `denial:not_a_member`
      due to the known 3J.2 RLS read-policy limitation (V13+
      carry-forward closes this for both 3J.2 and 3J.3 at
      once).
- [ ] Self-invite explicitly allowed at the policy layer (caller
      already holds `MEMBERSHIP_INVITE`); the V6 unique
      constraint refuses → 409 (same path as any duplicate).
      `self-invite when caller is already member — 409` asserts
      the behaviour.
- [ ] **`Idempotency-Key` header is shape-only and explicitly
      non-functional in 3J.3.** Accepted and propagated into
      `WriteContext.idempotencyKey`; NOT used to dedupe retries.
      Controller method KDoc states "clients should NOT assume
      retry-safe behaviour on this endpoint yet." Dedupe
      semantics land alongside Phase 4A's patient-create flow.
- [ ] `requestId` parity: response-body `requestId` equals
      response-header `X-Request-Id`. Asserted in integration
      test.
- [ ] `docs/security/phi-exposure-review-3j-3.md` landed.
      Risk: None (tenant metadata; no clinical path).
- [ ] Tests:
  - `InviteTenantMembershipValidatorTest` (5): happy path,
    blank slug, malformed slug, OWNER role accepted at
    validator layer (policy enforces escalation guard), ADMIN
    role accepted at validator layer.
  - `InviteTenantMembershipIntegrationTest` (17): 401 unauth;
    OWNER invites MEMBER 201 + audit; OWNER invites ADMIN 201;
    OWNER invites OWNER 201; ADMIN invites MEMBER 201; ADMIN
    invites ADMIN 201; **ADMIN invites OWNER 403 +
    denial=insufficient_authority**; MEMBER invites 403;
    non-member invites 403 + denial=not_a_member; suspended
    tenant 403 + denial=tenant_suspended; unknown slug 403 +
    denial=not_a_member; non-existent target user 422
    user_not_found; duplicate membership 409; self-invite
    409; missing userId 422; missing role 422; requestId
    parity; Idempotency-Key passthrough.
- [ ] Existing 242 tests still green; total after 3J.3:
      **265/265 across 51 suites (+23)**.
- [ ] Carry-forward closed in 3J.3:
  - First membership-invite endpoint (3J.1 initial ledger
    item "POST /api/v1/tenants/{slug}/memberships" / 3J.2
    inherited "Remaining tenancy write endpoints")
- [ ] Carry-forward inherited from 3J.2 (unchanged by 3J.3):
  - V13+ SECURITY DEFINER resolver to close
    MEMBERSHIP_SUSPENDED → NOT_A_MEMBER collapse (applies to
    both endpoints)
  - Audit payload column / structured mutation diff
  - ArchUnit WriteGate-exclusivity rule → 3I
  - PhiRlsTxHook sibling → 4A
- [ ] Carry-forward opened by 3J.3:
  - MEMBERSHIP_ROLE_UPDATE authority + endpoint (promote /
    demote) → 3J.N — the next natural tenancy write slice
  - Member removal endpoint (`DELETE /memberships/{id}`) →
    3J.N — pairs naturally with role-update
  - Idempotency-Key functional dedupe — still carry-forward
    to 4A; confirmed shape-only for 3J.3
  - Custom JSON deserialiser for `MembershipRole` that
    returns 422 on invalid enum (currently 400 via Jackson)
    → minor hardening slice

#### 3.4.4 Phase 3J.N — Membership role update + revocation

Third concrete command-family through the 3J.1 framework. Closes
the `MEMBERSHIP_ROLE_UPDATE` + `MEMBERSHIP_REMOVE` authorities
and establishes Medcore's first **aggregate-state invariant**
(last-OWNER). Two endpoints ship together because they share the
authority map update, the last-OWNER invariant, the audit shape
discipline, and the escalation-guard pattern.

- [ ] `PATCH /api/v1/tenants/{slug}/memberships/{id}` — role
      change; 200 OK with `WriteResponse<MembershipResponse>`.
- [ ] `DELETE /api/v1/tenants/{slug}/memberships/{id}` —
      soft-delete via status → REVOKED; 204 No Content with
      `X-Request-Id` header for correlation.
- [ ] New authority `MedcoreAuthority.MEMBERSHIP_ROLE_UPDATE`
      added; OWNER + ADMIN sets updated; tests updated.
- [ ] Six-file command stack per endpoint in
      `com.medcore.tenancy.write`: Command, Validator (role-update
      only — revoke has no body), Policy, Handler, Auditor,
      snapshot type. Pattern identical to 3J.2/3J.3.
- [ ] `LastOwnerInvariant` helper injected into both handlers.
      Acquires `PESSIMISTIC_WRITE` row lock on active-OWNER rows
      via `findAndLockByTenantRoleStatus` repo method with
      `@Lock(LockModeType.PESSIMISTIC_WRITE)`. Concurrent
      demotions serialise correctly.
- [ ] `WriteConflictException(code)` added to `platform/write/`;
      `GlobalExceptionHandler.onWriteConflict` maps to 409
      `resource.conflict` with `details.reason = <code>`.
- [ ] Three escalation guards in place and tested:
  - Base-authority check (policy): `MEMBERSHIP_ROLE_UPDATE`
    or `MEMBERSHIP_REMOVE` required.
  - Promotion-to-OWNER guard (policy): `newRole == OWNER`
    requires `TENANT_DELETE`.
  - **Target-OWNER guard (handler, new pattern):**
    `target.role == OWNER` requires caller hold `TENANT_DELETE`.
    Evaluated in the handler inside the gate's transaction
    because the target row is visible only after
    `TenancyRlsTxHook` sets the RLS GUC. Thrown
    `WriteAuthorizationException` is caught by `WriteGate` and
    routed through `onDenied` (framework extension).
- [ ] **Last-OWNER invariant** (ADR-007 §2.12): every tenant
      retains ≥1 ACTIVE OWNER at all times. Enforced via
      `LastOwnerInvariant.assertAtLeastOneOtherActiveOwner()` with
      pessimistic locking; throws `WriteConflictException("last_owner_in_tenant")`
      → 409. Sole OWNER cannot demote or revoke themselves.
- [ ] V13 migration
      `V13__tenancy_membership_rls_admin_read.sql`:
  - `medcore_rls_helper` role (NOLOGIN, NOSUPERUSER, BYPASSRLS)
    owns RLS-helper functions.
  - `tenancy.caller_is_tenant_admin(tenant_id, caller_id)`
    SECURITY DEFINER function, owned by `medcore_rls_helper`,
    `SET search_path = tenancy, pg_temp`, EXECUTE granted to
    `medcore_app` + `medcore_migrator` (REVOKE FROM PUBLIC
    first).
  - New OR'd SELECT policy `p_membership_select_by_admin_or_owner`
    calls the SECURITY DEFINER function (breaks RLS recursion).
  - GRANT USAGE on schema `tenancy` + SELECT on
    `tenant_membership` to `medcore_rls_helper` (BYPASSRLS only
    skips RLS, grants still required).
- [ ] `FlywayMigrationStateCheck.MIN_EXPECTED_INSTALLED_RANK`
      bumped from 12 to 13.
- [ ] `MedcoreApiApplicationTests.flyway history records expected
      migrations in order` updated for V13.
- [ ] Two new `AuditAction` entries:
  `TENANCY_MEMBERSHIP_ROLE_UPDATED` (`tenancy.membership.role_updated`)
  and `TENANCY_MEMBERSHIP_REVOKED` (`tenancy.membership.revoked`).
  Registry discipline applied: enum + KDoc + test emission +
  review-pack callout.
- [ ] **Audit-shape contract extended from 3J.3:**
  - Success + denial rows use `resource_type = "tenant_membership"`
    AND `resource_id = <target membership UUID>` (always known
    — URL path carries the id).
  - Success `reason` encodes transition tokens:
    `intent:tenancy.membership.update_role|from:OWNER|to:ADMIN`
    or `intent:tenancy.membership.remove|prior_role:OWNER`.
  - Denial `reason` carries closed-enum `WriteDenialReason.code`
    slug.
  - `(resource_type, outcome)` remains the canonical query pair
    — 3J.N asymmetry is resolved (target always exists here).
- [ ] **No-op suppression** on both endpoints:
  - PATCH-to-same-role → 200 no audit row, row_version unchanged.
  - DELETE-of-already-REVOKED → 204 no audit row, no state change.
  - Inherits 3J.2 precedent.
- [ ] **Cross-tenant ID probing masked as 404** — if the
      `{membershipId}` belongs to a different tenant than
      `{slug}`, both handlers throw `EntityNotFoundException`.
      Response body identical to "unknown membership" → no
      existence disclosure.
- [ ] `docs/security/phi-exposure-review-3j-n.md` landed.
      Risk: None (tenancy metadata; no clinical path).
- [ ] ADR-007 additions:
  - §2.12 — Last-OWNER invariant (NORMATIVE)
  - §2.13 — State-dependent authorization in the handler
    (WriteGate framework extension)
  - §2.14 — V13 admin-read RLS policy + SECURITY DEFINER helper
- [ ] Tests:
  - `UpdateTenantMembershipRoleValidatorTest` (3): happy path,
    blank slug, uppercase slug.
  - `UpdateTenantMembershipRoleIntegrationTest` (20): full
    matrix, escalation guards, last-OWNER 409s, no-op 200
    no-audit, cross-tenant 404, requestId parity, SUSPENDED
    OWNER doesn't count toward active-OWNER floor.
  - `RevokeTenantMembershipIntegrationTest` (13): full matrix,
    target-OWNER guard, last-OWNER 409s, idempotent-REVOKED
    204 no-audit, cross-tenant 404.
  - `MembershipRoleAuthoritiesTest`: updated cardinality +
    MEMBERSHIP_ROLE_UPDATE presence assertion.
- [ ] Existing 265 tests still green; total after 3J.N:
      **301/301 across 54 suites (+36)**.
- [ ] Carry-forward closed by 3J.N:
  - `MEMBERSHIP_ROLE_UPDATE` authority + promote/demote endpoint
    (3J.3 → 3J.N)
  - `DELETE /memberships/{id}` member-removal endpoint
    (3J.3 → 3J.N)
  - Partial closure of "V13+ SECURITY DEFINER `tenancy.resolve_authority`"
    — V13 delivered a narrower `caller_is_tenant_admin` helper
    that serves the admin-read use case. The MEMBERSHIP_SUSPENDED
    collapse from 3J.2 (caller-suspended-cannot-see-tenant) is
    NOT fixed by this (different code path — V8's tenant-level
    policy is unchanged) and remains a carry-forward.
- [ ] Carry-forward opened by 3J.N:
  - Deferred CHECK trigger at commit time for the last-OWNER
    invariant (closes the phantom-INSERT window of the
    pessimistic-lock approach) → Phase 7 or earlier if warranted.
  - Membership-role-update audit payload column for structured
    `from`/`to` capture (currently encoded in `reason` as
    closed-enum tokens) → Phase 7 bundled with the audit-schema-
    evolution ADR.
- [ ] Carry-forward INHERITED unchanged from 3J.3:
  - MEMBERSHIP_SUSPENDED → NOT_A_MEMBER RLS collapse (V8 tenant
    policy; separate from V13's membership-table expansion)
  - Audit payload column for before/after state → Phase 7
  - If-Match precondition header on PATCH → 3L or when a client
    demands it
  - ArchUnit WriteGate-exclusivity rule → 3I
  - PhiRlsTxHook sibling → 4A
  - Custom MembershipRole deserialiser for 422 on invalid enum
    (still valid for 3J.N's PATCH body parsing)

### 3.5 Phase 3I — Deployment baseline + CI enforcement

Phase 3I splits into multiple sub-slices per the roadmap (ArchUnit
governance, CI pipeline, Terraform dev environment, AWS Secrets
Manager implementation, deploy-on-merge workflow). Sub-slices land
independently so governance gates (3I.1/3I.2) can precede
infrastructure (3I.3+).

#### 3.5.1 Phase 3I.1 — ArchUnit machine-enforced invariants

Closes the ADR-007 §2.10 "review-gated until 3I" carry-forward
into CI-enforced rules that fail the build on any PR violating
the mutation perimeter, module boundaries, or audit discipline.

- [ ] `com.tngtech.archunit:archunit-junit5:1.3.0` added as
      `testImplementation`; wired into the normal `./gradlew test`
      task (no separate `archTest` task — lightweight rules
      don't warrant the split).
- [ ] Three test suites in
      `apps/api/src/test/kotlin/com/medcore/architecture/`:
  - `MutationBoundaryArchTest` (rules 1–5, 12)
  - `ModuleBoundaryArchTest` (rules 6–8)
  - `SecurityDisciplineArchTest` (rules 9–11, split 10 into
    class-level + method-level)
- [ ] **Mutation-boundary rules.**
  - Rule 1: JPA repositories accessed only from `..write..`,
    `..service..`, `..persistence..`, `..platform..`, `..identity..`.
  - Rule 2: `JpaRepository.save / saveAll / delete / deleteById /
    deleteAll / deleteAllById` called only from `..write..`,
    `..identity..`, `..persistence..`.
  - Rule 3: Controllers (`..api..`) do NOT reference JPA
    repositories at all (strict — reads go through services).
  - Rule 4: `AuthzPolicy` implementations reside in `..write..`.
  - Rule 5: `WriteAuditor` implementations reside in `..write..`.
  - **Rule 12 (entry-point exclusivity):**
    `WriteGate.apply(...)` is invoked only from classes in
    `..api..` packages.
- [ ] **Module-boundary rules.**
  - Rule 6: Identity does not depend on tenancy.
  - Rule 7: Tenancy does not depend on `identity.persistence..`
    or `identity.service..` (narrow `..write..` exception
    documented for `existsById` reads in
    `InviteTenantMembershipHandler`).
  - Rule 8: `platform.audit..` does not depend on business
    modules (keeps the dependency arrow one-way).
- [ ] **Security-discipline rules.**
  - Rule 9: `AuditEventCommand` constructed only in `..write..`,
    `..service..`, `..audit..`, `..persistence..`, and the
    explicitly allow-listed cross-cutting pre-controller hooks
    (`..identity..`, `com.medcore.platform.security..`,
    `com.medcore.tenancy.context..`). Each allow-listed package
    has KDoc explaining why no handler layer exists at that
    emission site.
  - Rule 10 (class-level): No `@Transactional` on controller
    classes (`..api..`).
  - Rule 10 (method-level): No `@Transactional` on controller
    methods.
  - Rule 11: `AuditAction` references live only in sanctioned
    layers (same allow-list as Rule 9 + `platform.audit..`).
- [ ] **Every rule carries a descriptive `.as(...)` message**
      pointing at the architectural contract + relevant ADR
      section. CI failure tells the contributor exactly which
      invariant they violated and how to fix.
- [ ] **Allow-listed exceptions documented in KDocs:**
      `IdentityProvisioningService` JIT provisioning emits
      audit pre-controller; `AuditingAuthenticationEntryPoint`
      emits 401-audit via Spring Security entry point;
      `TenantContextFilter` emits tenant-context audit at
      filter time. All three run BEFORE any controller dispatch,
      so the "domain-emits-audit" principle routes through
      pre-controller infrastructure instead.
- [ ] All existing 301 tests still green; +13 ArchUnit rule
      tests added. **314/314 across 57 suites**.
- [ ] ADR-007 §2.10 updated: "Review-gated until Phase 3I" →
      "Machine-enforced in Phase 3I.1 via 12 ArchUnit rules"
      with enumeration + rationale for each allow-listed
      cross-cutting exception.
- [ ] Carry-forward closed in 3I.1:
  - "ArchUnit rule: WriteGate is exclusive mutation entry point"
    (3J.2 → 3I.1) — rule 12 lands.
- [ ] Carry-forward opened by 3I.1 (none new; all captured
      allow-list items are narrow + KDoc-documented, not
      future-scope carry-forwards).

#### 3.5.2 Phase 3I.2 — CI enforcement

Machine-enforces the governance + security disciplines from
ADR-005 (product framework + Tier 3 contract), ADR-007 (mutation
perimeter via 3I.1 ArchUnit rules), and the PHI-leakage
disciplines from 3F/3G. Combined with 3I.1's ArchUnit rules, CI
becomes the merge-time gate: rules that 3I.1 established locally
are now unable to reach `main` without passing checks.

- [ ] `.github/workflows/ci.yml` defines three parallel jobs:
  - `test (gradle + ArchUnit + migrations)` — runs
    `./gradlew test --console=plain --no-daemon` in `apps/api`.
    Covers unit tests, integration tests, 3I.1 ArchUnit rules,
    and Flyway migration validation (implicit via
    Testcontainers applying V1–V13 per test-class startup).
  - `governance (trailer + doc-staleness)` — iterates the PR
    commit range (`github.event.pull_request.base.sha` …
    `github.event.pull_request.head.sha`; push events use
    `github.event.before` … `github.sha`) and verifies:
    (a) every commit body carries a non-empty `Roadmap-Phase:`
    trailer (ADR-005 §2.5, AGENTS.md §4.7.3); (b) no
    `docs/product/*.md` file has a passed `next_review:` date
    without a `Review-Deferred:<reason>` trailer elsewhere in
    the PR.
  - `secret-scan (gitleaks)` — downloads the Gitleaks binary
    (license-free, no paid action dependency) and runs
    `gitleaks detect --source . --redact` against the working
    tree + full git history.
- [ ] Trailer check uses **PR base/head SHAs** (not
      `origin/main..HEAD`) so it works correctly in rebased +
      squash-merged flows. Push-to-main uses
      `github.event.before..github.sha` for belt-and-braces
      enforcement; first-push edge case (zero-SHA base)
      short-circuits to pass.
- [ ] Doc-staleness check requires `Review-Deferred:` to carry
      a **non-empty reason** (ADR-005 §2.5 refinement). Empty
      deferrals are rejected — the reason makes the deferral
      reviewable in git history.
- [ ] Gitleaks runs as a binary invocation (not the paid GitHub
      Action). Pinned to v8.21.0 in the workflow. `--redact`
      replaces matched secrets in CI logs with `[REDACTED]` so
      log retention doesn't re-leak them.
- [ ] Test-job failure uploads `apps/api/build/reports/tests/`
      as an artifact with 7-day retention (debugging aid for
      integration-test failures that require the HTML report).
- [ ] Each gate has an actionable `::error::` message pointing
      the contributor at the remediation path in
      `docs/runbooks/ci-cd.md`.
- [ ] `docs/runbooks/ci-cd.md` landed covering:
  - Gate overview + failure interpretation per job
  - Branch-protection configuration (step-by-step, user-owned
    one-time setup in GitHub repo settings)
  - Editing-the-workflow governance (Tier 3 rules apply)
  - Cost + performance footprint
  - Escalation path for infra outages + override discipline
  - Future-extensions section (3I.2.b ktlint/detekt, 3I.3+)
- [ ] Local smoke-test verified before push:
  - Gitleaks v8.21.0 clean on current repo history + working
    tree (0 findings).
  - Python doc-staleness logic: 0 stale files across
    `docs/product/*.md` (all 7 within review window).
  - Trailer check: every commit in last-5 range carries
    non-empty `Roadmap-Phase:`.
- [ ] Branch-protection config step documented as **user-owned
      one-time UI action**. Not automated — bootstrapping
      secrets-to-enforce-secrets is circular; GitHub UI is the
      correct control plane.
- [ ] Test count unchanged: no application tests added (this
      slice is workflow + governance infra). Full suite still
      **314/314 across 57 suites** from 3I.1.
- [ ] Carry-forward closed in 3I.2:
  - `CI invariant: field-names-only in validation details`
    (3G → 3I.2) — covered indirectly: the 3I.1 ArchUnit rules
    + gradle test (which runs `ErrorResponsePhiLeakageTest`
    from 3G) now enforce the field-names-only discipline
    every PR. No separate CI rule needed.
- [ ] Carry-forward opened by 3I.2: none; future 3I sub-slices
      (3I.2.b ktlint/detekt, 3I.3 Dockerfile, 3I.4 Terraform,
      3I.5 AWS Secrets, 3I.6 deploy workflow) remain tracked
      under the original 3I entry.

#### 3.5.3 Phase 3I.2.b — ktlint + detekt (deferred)

<!-- TODO(content): populated when code-style rigor becomes a
     priority. Baseline-file curation on first-pass is the
     main scope risk; do as a dedicated slice. -->

#### 3.5.4 Phase 3I.3+ — Dockerfile / Terraform / deployment (deferred)

<!-- TODO(content): populated as each 3I sub-slice opens:
     - 3I.3: Dockerfile + build-info plugin
     - 3I.4: Terraform dev environment (VPC, ECS, RDS, S3, IAM)
     - 3I.5: AWS Secrets Manager real implementation
     - 3I.6: Deploy-on-merge workflow -->

### 3.6 Phase 3K — Production IdP decision + integration

Phase 3K is split: 3K.1 locks the vendor + identity contract
(governance-heavy, code-light); 3K.2 handles the concrete
vendor integration in `dev` (deferred to 3I.4 AWS landing).

#### 3.6.1 Phase 3K.1 — Identity contract + vendor ADR

Closes the 3A.3 "Production IdP ADR" carry-forward. Locks the
identity contract that Phase 4A (patient registry) depends on.

- [ ] `ADR-008` landed with status Accepted. Names **WorkOS** as
      the production workforce-identity broker.
- [ ] ADR-008 §2.2 frames WorkOS as a **broker / orchestration
      layer**, explicitly NOT Medcore's system of record. Medcore
      retains authority over `identity.user.status`, tenancy,
      audit linkage. Broker-swap is a one-file change to
      `ClaimsNormalizer`, not a data migration.
- [ ] ADR-008 §2.3 normative identity contract:
      `userId = Medcore-internal UUID`, `(issuer, subject) =
      external mapping`, `emailVerified=true` invariant,
      `preferredUsername` display-only. Contract stable across
      vendor swaps.
- [ ] ADR-008 §2.4 claims-normalization posture:
      `ClaimsNormalizer` is a **strict validator, not a
      transformer** (WorkOS emits clean OIDC; no vendor-specific
      remapping needed). Class exists as vendor-swap insurance.
- [ ] ADR-008 §2.5 tenant-mapping via lookup (not token claim) —
      locked.
- [ ] ADR-008 §2.6 **Medcore `PrincipalStatus` is authoritative**
      invariant. A cryptographically-valid token that maps to an
      `identity.user` row with `status != ACTIVE` MUST be
      rejected with 401 at the Medcore boundary. IdP-side
      deactivation propagates via token expiry; un-expired
      tokens are revoked at the Medcore layer.
- [ ] ADR-008 §2.8 MFA delegated to IdP, trusted via `amr`
      claim; no Medcore-side enforcement.
- [ ] `ClaimsNormalizer` implemented:
  - Validates `sub` present + non-blank
  - Validates `iss` present + non-blank
  - Validates `email_verified == true` when `email` present
  - No claim-name remapping (WorkOS is clean OIDC)
  - RFC 6750 compliance: error descriptions are ASCII-only
    (no em-dash / smart-punctuation — Spring Security
    rejects non-ASCII descriptions and falls back to the
    generic "Invalid token" message).
- [ ] `ClaimsNormalizer` wired into `MedcoreJwtAuthenticationConverter`
      — runs BEFORE `PrincipalResolver.resolve(...)`.
- [ ] `PrincipalStatusDeniedException` implemented:
  - Subclass of Spring's `DisabledException` (→
    `AuthenticationException` → 401)
  - Carries `actorId: UUID` + closed-enum `reasonCode:
    String` (`principal_disabled` | `principal_deleted`)
  - Thrown by `IdentityProvisioningService.resolve` when
    `entity.status != ACTIVE`
  - Caught by `AuditingAuthenticationEntryPoint` via
    cause-chain walk; emits `IDENTITY_USER_LOGIN_FAILURE`
    with `actorId` + `reason` populated from the exception
    (generic fallback reason stays `invalid_bearer_token`
    for non-status denials).
- [ ] No `IDENTITY_USER_LOGIN_SUCCESS` audit row emitted
      for rejected non-ACTIVE principals — the success event
      means "Medcore accepted the session," which we
      explicitly did not.
- [ ] `docs/runbooks/identity-idp.md` landed covering:
  - Mental model (broker vs system of record) — first
    paragraph, memorizable.
  - Environment matrix (local mock / dev / staging / prod).
  - Daily operations: disabling a user, deleting a user,
    rotating broker credentials, onboarding enterprise SSO.
  - Incident response: WorkOS outage, credential compromise,
    suspected compromised token.
  - Vendor-swap contingency playbook.
  - Token inspection + common failures reference.
  - Key files table (engineering reference).
- [ ] Tests:
  - `ClaimsNormalizerTest` (8 cases): happy path; missing
    email (passes); missing sub; blank sub; email with
    email_verified=false; email with email_verified missing;
    blank email (treated as absent); exception-description
    carries no PHI-shaped values (RFC 6750 ASCII-only).
  - `PrincipalStatusEnforcementIntegrationTest` (4 cases):
    ACTIVE baseline; DISABLED rejected with
    `reason=principal_disabled` + correct `actor_id`;
    DELETED rejected with `reason=principal_deleted`;
    DISABLED rejection emits NO `login_success` audit.
- [ ] Existing 314 tests still green; total after 3K.1:
      **326/326 across 59 suites (+12)**.
- [ ] Carry-forward closed in 3K.1:
  - "Production IdP ADR" (3A.3 → 3K.1) — ADR-008 lands with
    vendor + contract locked.
- [ ] Carry-forward opened by 3K.1: none.
  3K.2 (concrete WorkOS integration in `dev`) is deferred
  as a separate sub-slice alongside 3I.4 AWS landing; it
  continues under the original Phase 3K roadmap entry
  without becoming a ledger carry-forward from 3K.1.

#### 3.6.2 Phase 3K.2 — Concrete WorkOS integration in dev (deferred)

<!-- TODO(content): populated when Phase 3I.4 AWS substrate
     lands. Scope: Terraform WorkOS workspace config, application
     `application-dev.yaml` pointing at the dev WorkOS issuer,
     MFA-required policy assertion test, role-to-WorkOS-group
     mapping if needed (currently tenancy role is entirely
     Medcore-side so likely no mapping required). -->

### 3.7 Phases 3L, 3M

<!-- TODO(content): per-phase checklists populated as each phase opens
     per ADR-005 §2.4 (living-per-slice cadence). -->

### 3.8 Phase 4A — Patient registry

Phase 4A is the first clinical module and the first PHI-bearing
surface in Medcore. Split into multiple sub-slices per the 4A
design review pack (see conversation record / ADR-007 addenda).

#### 3.8.1 Phase 4A.0 — PHI RLS substrate

Closes the 3J.2-opened `PhiRlsTxHook` carry-forward. Ships the
framework + tests + ArchUnit discipline before any clinical
schema / endpoint lands, so 4A.1+ services inherit a stable
substrate instead of inventing RLS-GUC plumbing per slice.

- [ ] `PhiRequestContext(userId: UUID, tenantId: UUID)` data
      class — both fields non-nullable, partial-context
      structurally impossible.
- [ ] `PhiRequestContextHolder` — ThreadLocal-backed holder
      (NOT request-scoped Spring bean). Rationale: explicit
      set/clear lifecycle, MDC-compatible async-propagation
      story documented in KDoc for future slices.
- [ ] `PhiRequestContextFilter` — Spring filter with NORMATIVE
      ordering contract: runs AFTER Spring Security auth, AFTER
      TenantContextFilter, BEFORE any controller dispatch, BEFORE
      any `@Transactional` boundary. Registered at
      `SecurityProperties.DEFAULT_FILTER_ORDER + 20`. No-op for
      non-`/api/` paths (via `shouldNotFilter`). No-op when
      either principal or tenant context is missing (partial
      context rejected structurally).
- [ ] **Filter uses `try { ... } finally { holder.clear() }`**
      (refinement #1) — holder cleared unconditionally even
      when the chain throws. Prevents ThreadLocal leak to the
      next pooled request thread.
- [ ] `PhiSessionContext.applyFromRequest()` — bridges the
      holder to `TenancySessionContext` (which sets
      `app.current_user_id` + `app.current_tenant_id` via SET
      LOCAL). THROWS `PhiContextMissingException` if holder is
      empty. NEVER silently no-ops (refinement #3). Must be
      called inside an active transaction; propagates the
      underlying `IllegalStateException` if not.
- [ ] `PhiContextMissingException` extends `RuntimeException`;
      Phase 3G's `onUncaught` handler maps to 500
      `server.error`. Message logged but never echoed to the
      response body.
- [ ] `PhiRlsTxHook` — [WriteTxHook] implementation delegating
      to `PhiSessionContext.applyFromRequest()`. Registered as
      a `@Component`; future PHI write gates wire this via
      their `@Bean` construction instead of the tenancy-scope
      `TenancyRlsTxHook`.
- [ ] `TenancyRlsTxHook` KDoc updated to describe its
      relationship with the new PHI-aware sibling. No
      behavioural change to tenancy-scope writes.
- [ ] **ArchUnit Rule 13** (`ClinicalDisciplineArchTest`): every
      class in `com.medcore.clinical..service` MUST depend on
      `PhiSessionContext`. Vacuously true in 4A.0 (rule uses
      `.allowEmptyShould(true)` with KDoc explaining that the
      allowance is removed once 4A.1's patient service lands).
- [ ] **Async-propagation documentation** (refinement #5):
      `PhiRequestContextHolder` KDoc explicitly states that
      ThreadLocal does NOT propagate to async threads; future
      async execution must snapshot on the dispatching thread
      and restore on the worker thread (MDC pattern).
      Placeholder for future `PhiContextPropagator` utility
      when the first async-PHI path lands.
- [ ] **No clinical schema, no migration, no endpoints** —
      4A.0 is pure platform substrate. Patient table lands in
      4A.1.
- [ ] Tests (16):
  - `PhiRequestContextHolderTest` (4): empty/set/clear/thread-
    isolation.
  - `PhiSessionContextTest` (3): GUCs set inside tx; empty
    holder throws PhiContextMissingException; no-tx throws
    underlying IllegalStateException.
  - `PhiRequestContextFilterTest` (6): happy path populates +
    clears; no principal no-op; anonymous principal no-op;
    partial-context (no tenant) no-op; non-api path bypassed;
    holder cleared even when chain throws.
  - `PhiRlsTxHookTest` (2): sets both GUCs via WriteGate tx;
    empty holder throws.
  - `ClinicalDisciplineArchTest` (1): Rule 13 vacuously true
    in 4A.0.
- [ ] Existing 326 tests still green; total after 4A.0:
      **342/342 across 64 suites (+16)**.
- [ ] ADR-007 §7.2 added: `PhiRlsTxHook` sibling carry-forward
      CLOSED in 4A.0. ADR-007 §2.11 updated to describe both
      hooks (tenancy-scope vs PHI-scope).
- [ ] Carry-forward closed in 4A.0:
  - `PhiRlsTxHook` sibling for `app.current_tenant_id`
    (3J.2 → 4A.0)
- [ ] Carry-forward opened by 4A.0: none.
  `PhiContextPropagator` async helper is a future-phase
  note in the holder KDoc, not a ledger row (YAGNI).

#### 3.8.2 Phase 4A.1 — Patient schema (first PHI-bearing table)

Medcore's first PHI-bearing SQL surface. Lands `clinical.patient`
+ `clinical.patient_identifier` + both-GUCs RLS policies +
PATIENT_* authorities. **Ships zero application-level read/write
access** — no service, no handler, no controller; the surface
is dormant until 4A.2 wires the WriteGate perimeter.

- [ ] `V14__clinical_patient_schema.sql` — `clinical` schema +
      `clinical.patient` (22 columns, FHIR-aligned) +
      `clinical.patient_identifier` satellite. `fuzzystrmatch`
      extension enabled for phonetic indexing.
- [ ] **Both-GUCs RLS** on both tables — every SELECT / INSERT
      / UPDATE / DELETE policy keys on BOTH `app.current_tenant_id`
      AND `app.current_user_id` via
      `NULLIF(current_setting(..., true), '')::uuid`.
      Missing either GUC fails closed.
- [ ] **Role-gated writes** — INSERT / UPDATE / DELETE policies
      require the caller's ACTIVE membership to carry
      `role IN ('OWNER', 'ADMIN')` via a
      `tenancy.tenant_membership` EXISTS subquery.
- [ ] **Soft-delete hiding** — SELECT policy excludes
      `status = 'DELETED'`. MERGED_AWAY stays visible (merge-
      unwind workflow).
- [ ] **Identifier transitivity** — `clinical.patient_identifier`
      policies delegate visibility to the parent row via
      `EXISTS (SELECT 1 FROM clinical.patient p WHERE p.id =
      patient_identifier.patient_id)`. Subquery runs under
      `p_patient_select`, so all parent-row gates inherit.
- [ ] **Recursion analysis documented inline** in V14 —
      subqueries terminate via V13's SECURITY DEFINER helper
      (`medcore_rls_helper` BYPASSRLS) or V8's own-row policy.
- [ ] **CHECK constraints, database-enforced** (not app-
      enforced): `administrative_sex ∈ {male, female, other,
      unknown}`, `status ∈ {ACTIVE, MERGED_AWAY, DELETED}`,
      `mrn_source ∈ {GENERATED, IMPORTED}`,
      `sex_assigned_at_birth ∈ {M, F, UNK}` (nullable),
      `ck_clinical_patient_merged_fields_coherent` (merge
      fields coherent with status), `patient_identifier.type ∈
      {MRN_EXTERNAL, DRIVERS_LICENSE, INSURANCE_MEMBER, OTHER}`
      — **SSN deliberately absent** (deferred to its own
      compliance-review slice).
- [ ] **UNIQUE (tenant_id, mrn)** — prevents duplicate Medcore-
      minted MRNs within a tenant.
- [ ] **Duplicate-detection-aware indexes** —
      `(tenant_id, dob, lower(family), lower(given))` exact-
      match index + `(tenant_id, soundex(family))` phonetic
      index. Anticipates 4A.2's duplicate-warning handler
      without extra migration work.
- [ ] **TEXT + CHECK for every enum column** (NOT native
      Postgres ENUM) — consistent with `tenant.status`,
      `tenant_membership.role`, `tenant_membership.status`,
      `audit_event.actor_type`, `audit_event.outcome`.
      Migrating to native ENUMs is a cross-cutting normalization
      slice with its own ADR if we ever do it.
- [ ] **Kotlin model enums** (closed): `AdministrativeSex`
      (wire values `male` / `female` / `other` / `unknown`),
      `PatientStatus`, `MrnSource`, `PatientIdentifierType`.
- [ ] **JPA entities** — `PatientEntity` + `PatientIdentifierEntity`.
      Regular mutable classes (not `data class`),
      `@Version`-annotated `row_version`, protected no-arg
      constructor. `administrative_sex` stored as FHIR wire
      value via a typed property accessor.
- [ ] **Repositories** — `PatientRepository` +
      `PatientIdentifierRepository`, both `JpaRepository<E, UUID>`
      with zero query methods. **No application-code callers**
      in 4A.1; consumers wire in 4A.2.
- [ ] **`MedcoreAuthority` +3 entries**: `PATIENT_READ`,
      `PATIENT_CREATE`, `PATIENT_UPDATE`. Wire strings
      `MEDCORE_PATIENT_READ` / `..._CREATE` / `..._UPDATE`.
      Registry-discipline pattern (ADR-005 §2.3) followed:
      enum update + `MembershipRoleAuthorities` update +
      `MembershipRoleAuthoritiesTest` update, all in the
      same slice.
- [ ] **Role map extension** — OWNER + ADMIN gain all three
      PATIENT_* authorities; MEMBER gains `PATIENT_READ` only
      (documented simplification — clinical role
      differentiation is a future slice).
- [ ] **`FlywayMigrationStateCheck.MIN_EXPECTED_INSTALLED_RANK`
      bumped 13 → 14** so stale-schema deployments refuse to
      start.
- [ ] **`clinical` in Flyway scan path** — both
      `application.yaml` and `flyway.conf` updated.
- [ ] **ArchUnit Rule 13** — stays `.allowEmptyShould(true)`
      (vacuously true in 4A.1 because no `..clinical..service..`
      class exists yet). Allowance removed in 4A.2 when
      `PatientService` lands.
- [ ] **Tests (16 new in 4A.1)**:
  - `PatientSchemaRlsTest` (10): missing-tenant-GUC closed,
    missing-user-GUC closed, cross-tenant isolation,
    SUSPENDED-member blind, DELETED excluded, non-member
    blind across tenants, identifier transitivity, OWNER
    INSERT success, MEMBER INSERT refused, cross-tenant
    UPDATE silently zero rows.
  - `PatientEntityMappingTest` (6): JPA roundtrip (patient),
    JPA roundtrip (identifier), UNIQUE (tenant_id, mrn)
    refused, CHECK administrative_sex refused,
    CHECK status refused,
    CHECK merged-fields-coherent refused.
- [ ] **17 existing test resets updated** to wipe
      `clinical.patient_identifier` + `clinical.patient` before
      `tenancy.tenant_membership` (FK-dependency-order
      cleanup). No behavioural change to any existing test.
- [ ] **PHI-exposure review** — `docs/security/phi-exposure-
      review-4a-1.md`. Risk determination: Low. First slice
      that lands a PHI SQL surface; zero application
      reachability until 4A.2.
- [ ] Existing 342 tests still green; total after 4A.1:
      **358/358 across 66 suites (+16)**.
- [ ] Carry-forward closed in 4A.1: none (4A.1 closes no
      prior carry-forward; it establishes the PHI substrate
      that 4A.2+ consumes).
- [ ] Carry-forward opened by 4A.1: none. (The ArchUnit Rule
      13 `.allowEmptyShould(true)` allowance is a pre-existing
      4A.0 note, tracked to close with 4A.2 — it is NOT a
      4A.1 carry-forward.)

#### 3.8.3 Phase 4A.2 — First PHI write path (create + update)

Medcore's first REAL PHI write surface. Closes the ArchUnit
Rule 13 `.allowEmptyShould(true)` allowance opened in 4A.0.
Proves the 4A.0 substrate + 4A.1 schema compose into a
defense-in-depth perimeter under real end-to-end HTTP traffic.

- [ ] `V15__patient_mrn_counter.sql` — per-tenant MRN counter
      table with both-GUCs RLS + FK to `tenancy.tenant` +
      CHECK-constrained `format_kind` (extensibility point
      for future `ALPHANUMERIC` / `CHECK_DIGIT_MOD10`).
- [ ] `V16__fuzzystrmatch_public_schema.sql` — moves the
      `fuzzystrmatch` extension to `public` so `medcore_app`
      can call `public.soundex(...)` at runtime for phonetic
      duplicate detection. Defines the qualification
      discipline for future clinical SQL.
- [ ] `MrnGenerator` — atomic `INSERT ... ON CONFLICT DO UPDATE
      ... RETURNING` upsert. Monotonic per tenant. Rollback-
      safe (counter bump rolls back with the enclosing tx).
      Runs inside caller's tx (enforced via `check
      (isActualTransactionActive())`).
- [ ] **MRN format extensibility** — `MrnFormatKind` closed
      enum + `format_kind` column design so future
      alphanumeric / check-digit schemes add as branches in
      the generator without schema churn on patient rows.
- [ ] `CreatePatientCommand` write stack through 3J.1
      WriteGate using 4A.0 `PhiRlsTxHook`:
      Command → Validator (domain checks Bean Validation
      can't express) → AuthzPolicy (`PATIENT_CREATE` gate) →
      Handler (dup-detect + MRN mint + save) →
      Auditor (`PATIENT_CREATED` action).
- [ ] `UpdatePatientDemographicsCommand` write stack:
      Command + `Patchable<T>` three-state partial semantics
      → Validator (refuses `Clear` on required columns,
      validates Set values) → Policy (`PATIENT_UPDATE` gate)
      → Handler (load + `If-Match` compare + apply patches +
      no-op detect) → Auditor (`PATIENT_DEMOGRAPHICS_UPDATED`
      action with `fields:` slug — field names only, no
      values).
- [ ] `DuplicatePatientDetector` (first `..clinical..service..`
      class): exact match via
      `ix_clinical_patient_tenant_dob_family_given`, phonetic
      via `ix_clinical_patient_tenant_soundex_family` using
      qualified `public.soundex(...)`. Minimal-disclosure
      candidate shape (`{patientId, mrn}` only — never
      name/DOB).
- [ ] `X-Confirm-Duplicate: true` bypass header on the
      create endpoint. Default behaviour: 409 warning on
      match. Retry with header skips detection.
- [ ] **Three-state PATCH semantics** via `Patchable<T>` sealed
      class (`Absent` / `Clear` / `Set<T>`). Jackson binds
      body as `JsonNode`; mapper walks the tree. Only
      whitelisted fields reachable from PATCH (`mrn`, `status`,
      `merged_*`, `created_*`, `row_version` are NOT
      patchable).
- [ ] **`If-Match` header REQUIRED on PATCH.** Missing → 428
      `request.precondition_required` via
      `PreconditionRequiredException`. Stale → 409
      `resource.conflict` with `details.reason = stale_row`.
      Wildcard (`*`) rejected.
- [ ] `PatientController` at `/api/v1/tenants/{slug}/patients`
      — POST + PATCH only. GET deferred to 4A.5 (bundled
      with read-audit).
- [ ] `PatientWriteConfig` — first bean-wiring consumer of
      `PhiRlsTxHook`. Proves 4A.0 substrate composes with
      a real handler.
- [ ] New error codes: `clinical.patient.duplicate_warning`
      (409) + `request.precondition_required` (428). Registry
      discipline followed (entry in `ErrorCodes` +
      `@ExceptionHandler` in `GlobalExceptionHandler` + test
      coverage + review-pack callout).
- [ ] New `AuditAction` entries: `PATIENT_CREATED` +
      `PATIENT_DEMOGRAPHICS_UPDATED`. Both carry NORMATIVE
      audit-row shape contracts on their auditor KDocs.
      Denial paths use existing `AUTHZ_WRITE_DENIED` with
      clinical-scope reason slugs.
- [ ] **ArchUnit Rule 13 ACTIVATED** —
      `.allowEmptyShould(true)` removed.
      `DuplicatePatientDetector` is the first real
      consumer. Rule narrowed to `@Component`-annotated
      classes in `..clinical..service..` so exception + data
      classes in the package don't false-positive.
- [ ] `FlywayMigrationStateCheck.MIN_EXPECTED_INSTALLED_RANK`
      bumped 14 → 16 so stale-schema deployments refuse to
      start.
- [ ] Tests (32 new in 4A.2, across 6 new suites):
  - `MrnGeneratorTest` (5): bootstrap, monotonic, tenant-
    isolated, rollback-safety (tx fails → counter not
    consumed) ×2.
  - `CreatePatientIntegrationTest` (11): HTTP→DB happy
    path, OWNER / ADMIN / MEMBER role matrix, filter-level
    SUSPENDED / no-membership denials, cross-tenant block,
    missing-required + in-future DOB + invalid wire-value
    validation.
  - `UpdatePatientDemographicsIntegrationTest` (9): happy
    path with `row_version` bump, 428 missing If-Match,
    409 stale If-Match, null-clears-nullable,
    null-on-required → 422, empty-body 422, no-op
    suppression, cross-tenant 404, MEMBER denial with
    PATCH-specific denial reason slug.
  - `DuplicatePatientWarningTest` (5): exact-match 409,
    phonetic-match 409 (proves V16 relocation works),
    `X-Confirm-Duplicate` bypass 201, no-match 201,
    rollback-safe counter on dup warning.
  - `PatientCreateConcurrencyTest` (1): **50 parallel
    creates** yield 50 distinct contiguous MRNs with no
    gaps; counter advances exactly 50.
  - `PatientLogPhiLeakageTest` (1): no PHI tokens (names,
    DOB, language) appear in captured stdout after
    POST + PATCH. Extends 3F.1 LogPhiLeakageTest.
- [ ] **19 existing test resets updated** to wipe
      `clinical.patient_mrn_counter` before
      `tenancy.tenant_membership` — FK-dependency-order
      cleanup. No behavioural change to any existing test.
- [ ] **PHI-exposure review** — `docs/security/phi-exposure-
      review-4a-2.md`. Risk determination: Low. First
      slice with reachable PHI; three-layer defence-in-depth
      verified. Attack-surface analysis covers 12 scenarios
      including concurrency, rollback, cross-tenant probing,
      duplicate-warning enumeration, If-Match forgery, and
      payload-size DoS.
- [ ] Existing 358 tests still green; total after 4A.2:
      **390/390 across the full suite (+32)**.
- [ ] **Carry-forward closed in 4A.2**:
  - ArchUnit Rule 13 `.allowEmptyShould(true)` allowance
    (4A.0 → **4A.2**).
- [ ] **Carry-forward opened by 4A.2**:
  - Rate-limiting on duplicate-warning queries (future
    hardening slice when abuse observed).
  - IMPORTED MRN path + collision-retry (when a pilot
    clinic with legacy data demands it).
  - Real `Idempotency-Key` dedupe persistence (4A.2.1
    hardening OR 6A Stripe-webhook flow).

#### 3.8.4 Phase 4A.3 — Patient identifiers (first pattern reuse)

First slice to exercise `clinical-write-pattern.md` v1.0 as a
forcing function. Adds write path to the `clinical.patient_identifier`
satellite (already in V14/4A.1). Pattern held; three non-
breaking v1.1 amendments surfaced by the exercise.

- [ ] `V17__patient_identifier_role_gate.sql` — amends V14's
      identifier INSERT/UPDATE/DELETE policies to require
      OWNER/ADMIN in the membership-check subquery. Closes a
      factual error in V14's inline comment (which asserted
      the role gate was inherited from parent — it wasn't).
- [ ] `AddPatientIdentifierCommand` stack — Command + Validator
      (`blank` / `format` / `too_long` / `control_chars` /
      `valid_range` codes) + Policy (reuses
      `PATIENT_UPDATE`) + Handler (load patient, build entity,
      `saveAndFlush`) + Auditor (`PATIENT_IDENTIFIER_ADDED`).
- [ ] `RevokePatientIdentifierCommand` stack — Command + Policy
      + Handler (soft-delete via `valid_to = NOW()`, idempotent
      on already-revoked) + Auditor (`PATIENT_IDENTIFIER_REVOKED`
      with no-op suppression). No validator (no body on DELETE,
      3J.N precedent).
- [ ] `PatientIdentifierSnapshot` data class (includes
      `changed` flag for no-op suppression).
- [ ] Two new HTTP endpoints appended to existing
      `PatientController`: `POST /patients/{id}/identifiers`,
      `DELETE /patients/{id}/identifiers/{identifierId}`.
      No If-Match on DELETE (lifecycle transition, precedent
      from 3J.N).
- [ ] `AddPatientIdentifierRequest` + `PatientIdentifierResponse`
      DTOs appended to `PatientDtos.kt`. Type coercion via
      `enumValueOf<PatientIdentifierType>` with
      `WriteValidationException(field=type, code=format)`
      on unknown value.
- [ ] Two new `@Bean` gates in `PatientWriteConfig`
      (`addPatientIdentifierGate`, `revokePatientIdentifierGate`).
      Both use `PhiRlsTxHook`.
- [ ] Two new `AuditAction` entries with NORMATIVE
      shape-contract KDoc:
      `PATIENT_IDENTIFIER_ADDED` +
      `PATIENT_IDENTIFIER_REVOKED`.
      Reason slugs: `intent:clinical.patient.identifier.{add|revoke}|type:<TYPE>`
      — `type` token is closed-enum; `issuer` and `value`
      NEVER appear.
- [ ] **Authority reuse** — `PATIENT_UPDATE` from 4A.1 covers
      identifier management. New authority NOT introduced
      (decision logged in PHI review §0 + deliberate per
      template v1.1 §10 checklist prompt).
- [ ] `FlywayMigrationStateCheck.MIN_EXPECTED_INSTALLED_RANK`
      bumped 16 → 17. `MedcoreApiApplicationTests` expected-
      migrations list appended.
- [ ] **Template v1.1 amendments** in
      `docs/architecture/clinical-write-pattern.md`:
      (a) §10 checklist: "does this feature need a new
      authority?" prompt; (b) §7.2 `If-Match` scope
      clarification (PHI PATCH only, not DELETE/POST);
      (c) §1.1 RLS delegation option documented with
      satellite-role-gate caveat.
- [ ] Tests (28 new in 4A.3 across 4 new suites + 4 cases
      appended to `PatientSchemaRlsTest`):
  - `AddPatientIdentifierValidatorTest` (10) — every closed-
    enum code exercised.
  - `AddPatientIdentifierIntegrationTest` (8) — HTTP→DB happy,
    ADMIN allowed, MEMBER denied, cross-tenant 404, duplicate
    tuple 409, missing-issuer 422, unknown-type 422.
  - `RevokePatientIdentifierIntegrationTest` (5) — happy +
    idempotent (no second audit row) + cross-tenant 404 +
    cross-patient ID-smuggling 404 + MEMBER denial.
  - `PatientIdentifierLogPhiLeakageTest` (1) — `value` +
    `issuer` never appear in captured stdout after POST +
    DELETE.
  - `PatientSchemaRlsTest` (+4) — V17 proof: OWNER allowed
    INSERT; MEMBER refused on INSERT, UPDATE, DELETE at
    RLS layer.
- [ ] **PHI-exposure review** — `docs/security/phi-exposure-
      review-4a-3.md`. Risk determination: Low. First
      satellite PHI review; 11 attack-surface scenarios
      analysed.
- [ ] Existing 390 tests still green; total after 4A.3:
      **418/418 across the full suite (+28)**.
- [ ] **Carry-forwards closed in 4A.3**:
  - Identifier management as a separate slice (4A.2 PHI
    review) — closed.
  - V14 identifier RLS role-gate gap (surfaced during
    pattern-validation) — closed via V17.
- [ ] **Carry-forwards opened by 4A.3**:
  - CF-4A3-1 — partial UNIQUE index (`WHERE valid_to IS NULL`)
    to allow re-add-after-revoke. Extremely rare in practice.
  - CF-4A3-2 — identifier UPDATE command (value/issuer change
    without revoke-readd).
  - CF-4A3-3 — future pattern v1.2 amendments if surfaced by
    later slices.

#### 3.8.5 Phase 4A.3.1+ — Address/contact history, FHIR read, read audit, merge

<!-- TODO(content): populated as each 4A sub-slice opens:
     - 4A.3.1 (was 4A.3): Address + contact append-only history tables
     - 4A.4: GET /fhir/r4/Patient/{id} + US Core mapping
     - 4A.5: Read endpoint + read auditing (CLINICAL_PATIENT_ACCESSED)
     - 4A.6: Workflow-benchmark instrumentation (depends on 3L)
     - 4A.N: Merge workflow (dedicated slice) -->

### 3.9 Phases 4B–4G, 5A–5D, 6A–6D, 7, 8, 9, 10, 11, 12

<!-- TODO(content): populated as each phase opens. Phase 4+ DoD
     entries include the workflow-benchmark-met assertion per §2. -->

## 4. Production-readiness checklist (Phase 6D onwards)

A slice at Phase 6D or later additionally satisfies:

<!-- TODO(content): populated in follow-up slice. Structure:

     - [ ] Runbook rehearsal recorded
     - [ ] DR test performed this quarter
     - [ ] Access review performed this quarter
     - [ ] Claim ledger reconciled this quarter
     - [ ] No open Sev-1/Sev-2 incidents
-->

---

*Last reviewed: 2026-04-23 (Phase 4A.3 — first real pattern
reuse: identifier add + revoke through the clinical-write-
pattern v1.0 baseline. V17 closes V14's identifier RLS role-
gate gap. Template amended to v1.1 with three non-breaking
clarifications. 418/418 across the full suite). Next review:
2026-05-25, or on the next phase opening (whichever is sooner).*
