# Runbook — Observability (Phase 3F)

> Phase 3F.1 substrate: request-id propagation, proxy-aware client IP,
> MDC (request_id / tenant_id / user_id), Spring Boot 3.4 structured
> JSON logging. OpenTelemetry traces/metrics (3F.2), health/readiness
> probes (3F.3), and the chain verification job (3F.4) land in
> subsequent slices.
>
> Cadence: updated per 3F.* slice. Refreshed at the end of Phase 3F.

---

## Request ID

Every inbound HTTP request carries a correlation id:

- **Inbound header:** `X-Request-Id` by default. Accepted verbatim
  when it matches the configured format regex AND is within the
  length cap. Otherwise the filter mints a fresh UUIDv4 and ignores
  the inbound value (anti-poisoning).
- **Response header:** echoed on the same header name.
- **MDC:** populated under key `request_id` before Spring Security
  runs, so 401s, login failures, and auth-entry-point audit rows all
  carry the same id.
- **Audit row:** `audit.audit_event.request_id` column is populated
  by `RequestMetadataProvider`, which lifts the id from MDC. The
  chain is: inbound → filter → MDC → provider → audit row → response
  header. One id, four surfaces.

### Environment overrides

| Variable | Default | Purpose |
| ---- | ---- | ---- |
| `MEDCORE_REQUEST_ID_HEADER` | `X-Request-Id` | Header to read/write |
| `MEDCORE_REQUEST_ID_FORMAT` | UUID regex | Accept-list for inbound ids |
| `MEDCORE_REQUEST_ID_MAX_LENGTH` | `128` | Inbound length cap |
| `MEDCORE_REQUEST_ID_ECHO` | `true` | Echo on response header |

### Generating a request id for a manual test

```bash
# Let Medcore generate one:
curl -i http://localhost:8080/api/v1/me -H "Authorization: Bearer $TOKEN"
# Response carries X-Request-Id: <UUIDv4>.

# Supply your own (UUID format):
curl -i http://localhost:8080/api/v1/me \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Request-Id: 550e8400-e29b-41d4-a716-446655440000"
# Response echoes that exact value.

# Supply a malformed one:
curl -i http://localhost:8080/api/v1/me \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Request-Id: not-a-uuid"
# Response carries a fresh UUIDv4 (inbound was discarded).
```

### Correlating a log line to an audit row

```sql
-- From a log line showing request_id=<uuid>
SELECT id, recorded_at, actor_id, action, outcome, reason
FROM audit.audit_event
WHERE request_id = '<uuid>';
```

---

## Client IP — proxy-aware

By default the application reads the inbound `remote_addr` directly.
`X-Forwarded-For` is **ignored** when the trusted-proxy list is empty
(dev / test / direct-deploy).

To trust XFF from a specific upstream proxy, set:

```bash
# Example: AWS ALB egress CIDRs + a specific CloudFront edge.
MEDCORE_TRUSTED_PROXIES="10.0.0.0/16,203.0.113.42"
```

When configured, the resolver walks XFF right-to-left and returns the
first entry that is NOT in the trusted list as the originating client
IP. If every hop is trusted, falls back to `remote_addr`.

Do not set `MEDCORE_TRUSTED_PROXIES` to permissive values (e.g.,
`0.0.0.0/0`). That re-enables trivially spoofable client IPs.

---

## Structured logging

Spring Boot 3.4 structured logging is enabled by default. Format
defaults to **ECS** (Elastic Common Schema) for the console appender.
Each log line is a single JSON object on one line.

Default fields include:

- `@timestamp`
- `log.level`
- `log.logger`
- `message`
- MDC keys: `request_id`, `tenant_id` (when known), `user_id` (when
  known)
- Exception details when thrown

### Format override

```bash
MEDCORE_LOG_FORMAT=logstash   # or ecs (default), gelf
```

### Searching logs locally

```bash
# Find a specific request by id
docker logs medcore-api 2>&1 | jq -rc 'select(.request_id == "<uuid>")'

# Find all ERROR-level lines in the last hour for a specific tenant
docker logs --since 1h medcore-api 2>&1 \
  | jq -rc 'select(.["log.level"] == "ERROR" and .tenant_id == "<uuid>")'

# Watch a particular user's activity in real time
docker logs -f medcore-api 2>&1 \
  | jq -rc 'select(.user_id == "<uuid>")'
```

(Exact field paths depend on the selected format — check
`MEDCORE_LOG_FORMAT` and a sample line.)

---

## PHI & log discipline

**Rule 01 — no PHI in logs.** Phase 3F.1 adds a dedicated
`LogPhiLeakageTest` that fires known tokens (bearer tokens, email
patterns) through the request path and fails if they appear in log
output. The test is the operational control for Rule 01 inside the
observability slice. Any Phase 3F.2+ slice that adds log emission
sites MUST extend that test with coverage for the new sites.

**Things NOT logged by default:**

- Authorization header values (redacted at Spring Security layer).
- JWT token contents.
- Request bodies.
- Response bodies.
- Patient identifiers (none exist yet; Phase 4A onwards).

If a future slice needs to log something currently unlogged, the
slice MUST include a PHI-exposure review under `docs/security/` per
AGENTS.md §3.1.

---

## Troubleshooting

**Response has no `X-Request-Id` header.**
Check `MEDCORE_REQUEST_ID_ECHO`. Default is `true`; a deployment may
have disabled it for performance reasons that never materialise.
Flip back on for debugging.

**MDC shows `request_id` but not `tenant_id` or `user_id`.**
Expected for unauthenticated paths or requests that do not carry
`X-Medcore-Tenant`. Authenticated `/api/**` requests populate
`user_id` after the auth filter; the tenancy filter populates
`tenant_id` only when the header is present and resolution
succeeds.

**Every log line has the same `request_id`.**
MDC leaked across requests. Confirm the filter chain has not been
reordered; `RequestIdFilter` clears MDC in a `finally` block. If the
leak persists, check for rogue manual `MDC.put` calls outside the
filter (prohibited by convention).

**Client IP is `127.0.0.1` for all traffic in production.**
`MEDCORE_TRUSTED_PROXIES` is empty and the deployment sits behind a
proxy. Configure the proxy's egress CIDR range.

---

## Actuator probes (Phase 3F.3)

Medcore exposes health, liveness, readiness, and info endpoints
under `/actuator` via Spring Boot Actuator. All are anonymous; the
aggregate health payload is deliberately detail-free so no
component graph or DB version leaks to unauthenticated callers.

### Endpoints

| Path | Purpose | Auth | Detail |
| ---- | ---- | ---- | ---- |
| `/actuator/health` | Aggregate status | Anonymous | `{"status":"UP"}` only — `show-details: never` |
| `/actuator/health/liveness` | JVM process alive | Anonymous | `{"status":"UP"}` when the JVM answers; **does NOT depend on DB** — restarting won't fix a DB outage |
| `/actuator/health/readiness` | App ready to serve traffic | Anonymous | Depends on `readinessState` AND `db` indicator. The `db` check runs `SELECT 1` against the **runtime datasource** (`medcore_app` role), so a passing readiness probe proves the runtime request-path is healthy — not just that the JVM is up |
| `/actuator/info` | Build / deployment info | Anonymous | `{}` in 3F.3 (no info producers wired yet; build-info plugin is a separate slice) |

Every other actuator endpoint (`env`, `metrics`, `prometheus`,
`beans`, `mappings`, `configprops`, etc.) is **not exposed** on the
web and returns 404. The non-exposure is asserted by
`ActuatorProbesIntegrationTest`; accidentally adding
`management.endpoints.web.exposure.include: "*"` would flip these
to 401 (via the security chain) but is still a governance incident.

### Kubernetes / ECS probe configuration

```yaml
# Kubernetes Deployment pod-spec snippet
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 5
  failureThreshold: 3
```

```json
// ECS task-def health-check fragment (ALB target-group health check)
{
  "healthCheck": {
    "command": [
      "CMD-SHELL",
      "curl -fs http://localhost:8080/actuator/health/readiness || exit 1"
    ],
    "interval": 15,
    "timeout": 5,
    "retries": 3,
    "startPeriod": 60
  }
}
```

### Security posture

A dedicated `SecurityFilterChain` (`ActuatorSecurityConfig`) at
`@Order(1)` matches `/actuator/**` and permits anonymous access to
the four exposed paths above. Any other `/actuator/*` path under the
chain would default to `authenticated()`, but since such paths are
not exposed at the MVC layer they return 404 before the security
chain evaluates. The chain is belt; MVC exposure is braces. Both
must be maintained.

The `/api/**` chain is unchanged — bearer-token authentication is
still required. `ActuatorProbesIntegrationTest` includes a
regression test that asserts `/api/v1/me` remains a 401 anonymous.

### Troubleshooting

**Readiness probe failing, liveness OK.**
The app process is alive but its runtime datasource is unreachable.
Check:
- `MEDCORE_DB_APP_PASSWORD` is set and matches the role's actual
  password in the database.
- Network path from app to RDS / Postgres container is open.
- The `medcore_app` role has `LOGIN` and is not locked.

**Both probes failing.**
Usually a JVM-level issue (startup never completed, OOM, deadlock).
Check container logs; the app may not have reached the Spring Boot
`ApplicationStartedEvent` (which is when `DatabaseRoleSafetyCheck`
runs — if that refuses startup, Actuator never registers).

**Readiness returns 200 in test but not in production.**
Most likely the production `medcore_app` password sync hasn't run
(see `docs/runbooks/local-services.md` and Phase 3E notes). In
production the password is provisioned by ops out-of-band; the
application's VERIFY-only posture will log an error and refuse to
start if the password env var is blank.

---

## Error envelope correlation (Phase 3G)

Every 4xx / 5xx response carries the unified `ErrorResponse` envelope
with a `requestId` field populated from MDC. That field equals the
response's `X-Request-Id` header AND equals the `request_id` on any
audit row emitted during the request AND equals the `request_id`
field in every structured log line from the request. One value,
four surfaces.

When a caller reports an error response, ask for (or read from the
body) the `requestId`, then:

```sql
-- Find any audit rows for this request
SELECT recorded_at, action, actor_id, outcome, reason
  FROM audit.audit_event
 WHERE request_id = '<uuid>'
 ORDER BY recorded_at;
```

```bash
# Find every log line for this request
docker logs medcore-api 2>&1 | jq -rc 'select(.request_id == "<uuid>")'
```

Error codes emitted by the backend are a closed set documented in
`com.medcore.platform.api.ErrorCodes`. The codes (not the messages)
are the machine-readable signal; two 403 paths (`auth.forbidden` and
`tenancy.forbidden`) deliberately share the IDENTICAL message
`"Access denied."` so a probing caller cannot distinguish them
without authorization.

---

## Audit chain verification (Phase 3F.4)

A scheduled background job invokes `audit.verify_chain()` (V9,
Phase 3D) periodically to detect tamper in the audit hash chain.
The job is **detection only** — it never modifies audit data and
never invokes `audit.rebuild_chain()`.

### Behaviour matrix

| Outcome | Log level | Audit event |
| ---- | ---- | ---- |
| Clean chain | DEBUG (prod default) / INFO (dev when verbose-clean-log=true) | none — silence is the right signal |
| Broken chain | ERROR | `audit.chain.integrity_failed` (one per cycle; reason `breaks:<N>\|reason:<first-code>`) |
| Verifier infra failure (DB unreachable, function missing, permission denied) | ERROR | `audit.chain.verification_failed` (one per cycle; reason `verifier_failed`) |

### Environment configuration

| Variable | Default | Purpose |
| ---- | ---- | ---- |
| `MEDCORE_AUDIT_CHAIN_VERIFICATION_ENABLED` | `true` | Master switch. `false` disables the scheduler entirely. |
| `MEDCORE_AUDIT_CHAIN_VERIFICATION_CRON` | `0 0 * * * *` (hourly) | Spring `@Scheduled` cron expression, UTC-evaluated. Dev can shorten for fast feedback, e.g. `*/30 * * * * *` (every 30s). Note: Spring does not support a separate initial-delay for cron; first run is at the next matching cron time. |
| `MEDCORE_AUDIT_CHAIN_VERIFICATION_VERBOSE_CLEAN_LOG` | `false` | When `true`, clean-case verification logs at INFO. Default `false` emits at DEBUG (silent under default production logging). |

### Incident response — `audit.chain.integrity_failed` emitted

This is a **clinical safety incident**. The audit chain is
cryptographically linked; a break means at least one row was
modified, deleted, or re-ordered after it was appended. Steps:

1. **Preserve the current state.** Do NOT call `rebuild_chain()`
   as a first move — the function recomputes hashes based on
   current row content, which overwrites the forensic evidence of
   the break.
2. **Pull forensics.**
   ```sql
   -- Full break set for this cycle
   SELECT sequence_no, reason FROM audit.verify_chain();

   -- Compare stored vs. canonical for each break
   SELECT sequence_no, row_hash,
          audit.compute_chain_hash(audit.canonicalize_event(
              id, recorded_at, tenant_id, actor_type, actor_id,
              actor_display, action, resource_type, resource_id,
              outcome, request_id, client_ip, user_agent, reason
          ), prev_hash) AS recomputed
     FROM audit.audit_event
    WHERE sequence_no IN (...breakage sequence numbers...);
   ```
3. **Correlate** via `request_id` on all log lines in the window
   covering the break. Determine who / what had write access to
   the audit schema (should be nobody — `medcore_app` has
   `INSERT, SELECT` only, everything else revoked).
4. **Remediate** only after forensics: invoke
   `audit.rebuild_chain()` via the migrator role if (and only if)
   the content is confirmed correct and the hashes need
   recomputation. Remediation should produce its own audit trail
   (captured by the migration or ops journal, not by the app).

### Incident response — `audit.chain.verification_failed` emitted

This is an **infrastructure incident**, not a chain-integrity
incident. Common causes:

- Database connection refused (check connection pool, RDS health).
- `audit.verify_chain()` function missing (check Flyway history —
  V9 should be applied).
- Permission denied on `audit.verify_chain()` (check the grant to
  `medcore_app` from V9).

If `verification_failed` persists for more than 3 consecutive
cycles, manually invoke `SELECT audit.verify_chain()` via `psql` as
`medcore_app` to confirm state before escalating further.

### Deployment constraints — single-instance only

> ⚠️ **This scheduler is safe only in single-instance deployments.**
> Running Medcore horizontally (multiple pods / ECS tasks sharing
> one Postgres) will produce **duplicate `integrity_failed` /
> `verification_failed` audit events** — every instance's scheduler
> fires on the same cron and each observes the same chain state.
>
> Before Medcore ever runs multi-instance, a distributed lock
> (ShedLock against the shared DB, or equivalent) MUST land. This
> is tracked as carry-forward. Operators onboarding Medcore should
> verify the deployment remains single-instance by default
> (Phase 3I's ECS baseline is single-task). When horizontal
> scaling is needed, the ADR that introduces it must also land the
> distributed-lock substrate.

### Operator surface (deferred)

No HTTP endpoint exposes chain status or triggers manual
verification in 3F.4. That lands in Phase 3J alongside tenancy
writes + RBAC.

---

## Carry-forward tracked out of Phase 3F / 3G

- 3F.2: OpenTelemetry SDK + traces/metrics (the `/actuator/prometheus`
  endpoint that is currently 404 lands here).
- 3F.4 close-outs remaining: HTTP operator surface for manual
  verification / chain-status inspection → Phase 3J (alongside
  tenancy writes + RBAC).
- 3F.4: distributed lock for multi-instance deployments (ShedLock
  or equivalent) → ADR at the point Medcore runs multi-instance.
- Build-info / git-commit-info Gradle plugin → separate slice when
  useful (makes `/actuator/info` non-empty).
- 3G: 400 Bad Request responses remain framework-default — explicit
  follow-up carry-forward when a real caller cares.
- 3G: Audit emission on 403 access-denied path → deferred to 3J
  (needs RBAC for meaningful attribution).
- Log-aggregation / shipping infra is an explicit non-goal at Phase
  3F; revisited in Phase 3I (CloudWatch + deployment baseline).
