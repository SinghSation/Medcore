---
status: Active
last_reviewed: 2026-04-22
next_review: 2026-05-22
cadence: stable-amended-on-phase-close
owner: Repository owner
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

#### 3.1.2 Phase 3F.2 — OpenTelemetry traces/metrics

<!-- TODO(content): populated when 3F.2 opens. -->

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

#### 3.1.4 Phase 3F.4 — Chain verification scheduled job

<!-- TODO(content): populated when 3F.4 opens. -->

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

### 3.3 Phases 3H, 3I, 3J, 3K, 3L, 3M, 3F.2, 3F.4

<!-- TODO(content): per-phase checklists populated as each phase opens
     per ADR-005 §2.4 (living-per-slice cadence). Structure template:

     #### 3.X Phase NNN
     - [ ] Exit criterion summary
     - [ ] Specific test coverage
     - [ ] Specific audit events present
     - [ ] Specific artifacts (runbooks, ADRs, etc.) landed
     - [ ] Workflow benchmarks met (where applicable — Phase 4+ only)
     - [ ] Carry-forward resolved
-->

### 3.4 Phases 4A–4G, 5A–5D, 6A–6D, 7, 8, 9, 10, 11, 12

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

*Last reviewed: 2026-04-21 (Phase 3F.1 DoD populated alongside its
first slice). Next review: 2026-05-05, or on the next phase opening
(whichever is sooner).*
