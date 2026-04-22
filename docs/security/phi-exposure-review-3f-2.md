# PHI Exposure Review — Phase 3F.2 (OpenTelemetry Traces + Metrics)

**Slice:** Phase 3F.2 — Micrometer Observation API bridged to OpenTelemetry
SDK; auto-instrumentation for HTTP + JDBC; `@Observed` annotations on
`JdbcAuditWriter.write` and `ChainVerifier.verify`; OTLP push
exporters disabled by default; startup-visibility signals; hybrid
attribute filter (deny-list for auto-instrumentation, allow-list for
Medcore-custom observations); four new integration tests.

**Reviewer:** Repository owner (solo).

**Date:** 2026-04-22.

**Scope:** All Phase 3F.2 code and configuration changes. The slice
introduces new spans + metrics into the observability substrate; it
does NOT introduce any new PHI handling path. This review confirms
that span attributes, metric tags, and exported telemetry never
carry PHI.

**Risk determination:** None. Detailed rationale below.

---

## 1. What the slice emits (spans + metrics)

### 1.1 Auto-instrumented emissions

Spring Boot 3.4 auto-instruments HTTP server requests and JDBC
calls. The DEFAULT attributes captured (per Micrometer + OpenTelemetry
conventions current as of 2026-04) are:

- HTTP server: `http.request.method`, `http.response.status_code`,
  `http.route` (templated URI, e.g., `/api/v1/tenants/{slug}/me` —
  slug as a LITERAL placeholder, NOT the actual value), `uri`,
  `outcome`, `exception` (class name only).
- JDBC: `db.system` (postgresql), `db.statement` (the SQL TEMPLATE
  with `?` placeholders — prepared-statement parameter values are
  NOT captured), `db.operation`, `db.name`.

Fields that Medcore has verified are NOT captured by default and
are additionally protected by the deny-list filter:

- `http.request.header.*`, `http.response.header.*` (the
  `Authorization` header especially)
- `http.request.query.*`, `url.query`
- `http.request.body`, `http.response.body`
- `sql.parameters`, `db.statement.parameters`, `db.sql.parameters`

### 1.2 Medcore-custom emissions

Two `@Observed`-annotated methods produce custom observations:

- `medcore.audit.write` on `JdbcAuditWriter.write`. Attributes
  carried:
  - `medcore.audit.action` — closed-enum action code (e.g.,
    `identity.user.login.success`). Not PHI.
  - `medcore.audit.outcome` — SUCCESS / DENIED / ERROR enum. Not PHI.
  - Default `@Observed` attributes (`class`, `method`). Not PHI.
- `medcore.audit.chain.verify` on `ChainVerifier.verify`. Attributes:
  - `medcore.audit.chain.outcome` — `clean` / `broken` /
    `verifier_failed` literal. Not PHI.
  - Default `@Observed` attributes. Not PHI.

No other `medcore.*` observations exist in 3F.2.

### 1.3 Attributes deliberately NOT added

`JdbcAuditWriter.write` KDoc enumerates the prohibited attribute set
for compliance clarity:

- Actor IDs (`actor_id`), tenant IDs (`tenant_id`), resource IDs
  (`resource_id`).
- Actor display name, email, preferred username.
- Audit row `reason` content.
- Any column value of the row being appended.
- Any representation of the `AuditEventCommand` payload.

These fields live in the `audit.audit_event` table (access-
controlled via grants) and in MDC (request-scoped, cleared on
request exit). They do NOT belong in span attributes because:

- Span backends often have weaker access controls than the audit
  DB.
- Spans may be sampling-dropped — creating a disclosure path that
  varies by sampling rate.
- Span attribute storage in telemetry backends often has longer
  retention than Medcore's audit log.

---

## 2. Hybrid attribute filter — the runtime backstop

`ObservationAttributeFilterConfig` registers an `ObservationFilter`
that runs on every observation before it reaches any exporter. The
filter has two modes:

### 2.1 Auto-instrumented observations — deny list

Observation names NOT starting with `medcore.` are treated as
auto-instrumented. The filter strips attribute keys matching any
of `AUTO_INSTRUMENTATION_DENY_PATTERNS`:

```
http.request.header.*, http.response.header.*, header.*,
http.request.query.*, url.query, query.*,
sql.parameters, db.statement.parameters, db.sql.parameters,
http.request.body, http.response.body,
patient.*, user.email, user.name, user.display_name
```

This catches any future auto-instrumentation default flip (e.g.,
an OpenTelemetry release that starts capturing headers) without
Medcore code or config changes.

### 2.2 Medcore-custom observations — allow list

Observation names starting with `medcore.` are Medcore-authored and
treated strictly. Only keys in `MEDCORE_CUSTOM_ALLOW_PATTERNS` OR
matching a `STANDARD_OTEL_PREFIXES` prefix are retained:

```
MEDCORE_CUSTOM_ALLOW_PATTERNS:
  medcore.audit.action
  medcore.audit.outcome
  medcore.audit.chain.outcome

STANDARD_OTEL_PREFIXES:
  otel.*
  error
  exception.*
  code.*
  class
  method
```

Every other attribute is stripped. A future slice that adds, say,
`medcore.patient.id` to a span would be SILENTLY STRIPPED by the
filter until the allow list is updated in a deliberate code change.
That change MUST land with:

1. The new allow-list entry in the filter.
2. The equivalent addition to `TracingPhiLeakageTest`'s `isAllowedKey`.
3. An amendment to this PHI-exposure review.
4. A review-pack callout naming the new attribute and justifying
   why it is not PHI.

The four-way synchronised change creates four independent points of
friction against accidental PHI leakage via observation attributes.

---

## 3. Export defaults — disabled, with operator-visible signal

OTLP trace and metric endpoints default to **unset**. Medcore
collects spans and metrics in-process but never ships them. This
posture:

- Eliminates the risk of accidental phone-home on initial deploy.
- Requires an operator action to enable export (and therefore
  creates an intentional authorisation moment).

Silent-by-default is a known risk: engineers investigating a
production incident could assume traces exist and waste the
debugging window discovering they don't. Mitigated by:

- **Startup log:** `ObservabilityStartupReporter` logs at INFO the
  current export state on every `ApplicationReadyEvent`. When both
  export channels are unset, an additional WARN line makes the
  posture unmistakable.
- **`/actuator/info`:** `TelemetryExportInfoContributor` exposes
  `telemetry.traces.enabled`, `telemetry.metrics.enabled`, and the
  configured endpoints under the `info` actuator response. Operators
  can query via script or dashboard.

---

## 4. Sampling — 0.1 prod default, escalation documented

Production sampling defaults to 0.1 (10%) to keep trace volume
sustainable. At that rate, rare failures may not surface in any
sampled trace. Mitigation:

- **Runbook incident-response subsection** documents that
  operators MUST raise sampling to 1.0 for the duration of an
  active incident investigation (via
  `MEDCORE_TRACING_SAMPLING_PROBABILITY=1.0` at deploy time).
- Dev defaults to 1.0 (full sampling) via env var — fast-feedback
  loop for development engineers.

---

## 5. Log correlation — request_id vs trace_id

Phase 3F.1 added `request_id` to the structured-log output; Phase
3F.2 adds `trace_id` and `span_id` via Micrometer Tracing's MDC
bridge. All four identifiers appear on every structured log line
within a request scope.

**They are not the same:**

- `request_id` — internal Medcore correlation; persisted to
  `audit.audit_event.request_id`; present on ALL log lines (scheduler
  jobs, HTTP requests alike).
- `trace_id` — distributed-tracing correlation; present only when
  the request was sampled AND trace export is enabled; suitable
  for correlation across services in a multi-service deployment.

Runbook distinguishes the two and provides a combined-correlation
query example.

---

## 6. New test surfaces

- `TracingConfigIntegrationTest` (7) — beans exist; sampling
  default 0.1; OTLP endpoints unset.
- `AuditWriteObservationTest` (1) — real audit writes drive the
  timer; tag keys are the allow-list subset.
- `ChainVerifyObservationTest` (2) — chain verify drives the
  timer; outcome tag is in the closed set.
- `TracingPhiLeakageTest` (2) — no `medcore.*` meter carries keys
  outside the allow list; no tag values match obvious PHI shapes
  (email, SSN, bearer, date patterns).

`TracingPhiLeakageTest` is the runtime PHI-discipline anchor. It
will fail loudly on any future slice that:

- Adds an attribute to a `medcore.*` observation without updating
  the filter's allow list.
- Auto-instrumentation starts capturing a field that the filter's
  deny list does not already catch.
- A tag value somehow carries PHI-shaped data (email, SSN, date,
  bearer token).

---

## 7. Attack-surface considerations

### 7.1 Sampling-rate disclosure-path variance

At sampling 0.1, only 10% of spans are exported. If an attribute
leak is introduced but shows up in only 10% of traces, quarterly
reviews are less likely to catch it. Mitigation: tests assert the
filter + allow list on EVERY observation regardless of sampling
decision. Sampling applies at export time, not collection.

### 7.2 Collector-side compromise

If an operator configures `MEDCORE_OTLP_TRACING_ENDPOINT` pointing
at a compromised collector, the attacker sees spans. Mitigation:
span attributes carry no PHI (above); even with collector
compromise, patient data is not at risk. Operational risk (request
volume, URL patterns, error rates) exists and is accepted as the
trade-off for observability.

### 7.3 `/actuator/info` telemetry block anonymous

Phase 3F.3 makes `/actuator/info` anonymously accessible. The new
`telemetry` block reveals whether export is configured and to
which endpoint. The endpoint string IS operationally sensitive —
it could hint at infrastructure topology. Mitigation: treat the
endpoint as an operational secret by convention; deploy behind
the same network controls as other `/actuator/*` surfaces
(Phase 3F.3 runbook notes). No PHI.

### 7.4 Span attributes visible in error log lines

`medcore.audit.action` AND other allow-listed attributes will
appear on log lines that include span context. Those attributes
are non-PHI by construction (closed enums, outcome flags). No
action required.

---

## 8. Conclusion

Phase 3F.2 introduces no new PHI paths. The attribute filter
enforces allow-list discipline on Medcore-custom observations and
deny-list on auto-instrumented observations. Exports are disabled
by default. Sampling is prod-safe with documented incident
escalation. The runbook distinguishes `request_id` from
`trace_id` so correlation remains intentional.

**Risk: None.**
