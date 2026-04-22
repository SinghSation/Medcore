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

## Carry-forward tracked out of 3F.1

- 3F.2: OpenTelemetry SDK + traces/metrics.
- 3F.3: Spring Boot Actuator health + readiness probes (datasource,
  Flyway).
- 3F.4: `audit.verify_chain()` scheduled job.
- Log-aggregation / shipping infra is an explicit non-goal at Phase
  3F; revisited in Phase 3I (CloudWatch + deployment baseline).
