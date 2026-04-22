---
status: Active
last_reviewed: 2026-04-22
next_review: 2026-05-22
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

#### 3.4.2 Phase 3J.2+ — Endpoint slices

<!-- TODO(content): populated as each endpoint slice opens. Each
     slice's DoD row MUST assert: WriteGate is the only mutation
     entry point for the endpoint; onSuccess populates
     `intent:<command-slug>`; authz denial emits
     AUTHZ_WRITE_DENIED; integration tests cover happy path +
     cross-tenant RLS refusal + role-matrix. -->

### 3.5 Phases 3I, 3K, 3L, 3M

<!-- TODO(content): per-phase checklists populated as each phase opens
     per ADR-005 §2.4 (living-per-slice cadence). -->

### 3.6 Phases 4A–4G, 5A–5D, 6A–6D, 7, 8, 9, 10, 11, 12

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

*Last reviewed: 2026-04-22 (Phase 3J.1 DoD populated alongside its
substrate slice; Phase 3J / 3I reorder reflected in §3.4 + §3.5
per ADR-007). Next review: 2026-05-22, or on the next phase
opening (whichever is sooner).*
