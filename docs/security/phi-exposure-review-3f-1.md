# PHI Exposure Review — Phase 3F.1 (Request-ID + Structured Logging)

**Slice:** Phase 3F.1 — `RequestIdFilter`, `ProxyAwareClientIpResolver`,
`MdcUserIdFilter`, Spring Boot 3.4 structured JSON logging, end-to-end
`request_id` correlation into audit.

**Reviewer:** Repository owner (solo).

**Date:** 2026-04-21.

**Scope:** All Phase 3F.1 code and configuration changes. No clinical
resources exist yet; PHI fields are not handled by this slice. The
review covers metadata fields (identifiers, headers, IP addresses)
and log emission.

**Risk determination:** None (Phase A maturity, Phase 3 posture).
Detailed rationale below.

---

## 1. Data flows introduced by this slice

### 1.1 Request-id propagation

- **Inbound:** `X-Request-Id` header (caller-supplied) OR filter-
  generated UUIDv4.
- **Stored at:** MDC thread-local context, response header,
  `audit.audit_event.request_id` column (already existed per ADR-003
  §7; now populated end-to-end).
- **Logged at:** every structured log line emitted during the
  request lifecycle.
- **Not stored at:** any other row, any durable storage outside the
  audit chain.

**PHI classification:** request ids are opaque UUIDs (or caller-
supplied UUIDs matching the format regex). They carry no patient
identifier, no clinical content, no authentication material. They
are **not PHI**.

### 1.2 Client IP resolution

- **Inbound:** `remote_addr` (always) plus `X-Forwarded-For` (only
  when `trustedProxies` is non-empty AND the immediate peer is in
  that list).
- **Stored at:** `audit.audit_event.client_ip` column (already
  existed; now proxy-aware).
- **Logged at:** not logged directly by default (MDC carries
  request/user/tenant ids, not client IP).

**PHI classification:** client IP is classified as a limited
identifier under HIPAA 45 CFR 164.514(b)(2)(i)(B). It is PHI when
associated with patient data. In this slice no patient data exists
yet, so the IP is metadata of a non-PHI request. Audit rows carrying
client IP pre-date this slice (Phase 3C); this slice only changes
how IP is derived (proxy-aware), not what is stored or logged.

**Risk:** IP-based geolocation privacy is a concern in some
jurisdictions (state privacy laws, GDPR once in scope). Current
posture: IP is stored in audit only, never logged, never exposed in
response bodies. Sufficient for Phase A.

### 1.3 User-id MDC

- **Inbound:** derived from `SecurityContextHolder.authentication.principal`
  (a resolved `MedcorePrincipal` with an internal UUID).
- **Stored at:** MDC thread-local context.
- **Logged at:** every structured log line emitted after the auth
  filter runs.

**PHI classification:** Medcore's internal user id is an opaque UUID
not derived from any personal identifier. It maps to an
`identity.user` row in the database but is not itself a name, email,
SSN, MRN, or other limited identifier. **Not PHI** on its own;
becomes a limited identifier when joined with demographics — that
join is audited and access-controlled.

### 1.4 Tenant-id MDC

- **Inbound:** `X-Medcore-Tenant` header resolved via
  `TenantMembershipLookup`.
- **Stored at:** MDC thread-local context plus `audit.audit_event.tenant_id`
  (already existed).
- **Logged at:** structured log lines emitted after tenancy filter
  runs (header-resolved requests only).

**PHI classification:** Medcore's tenant id is an opaque UUID
identifying a provider practice. A tenant slug could be considered
PII of the practice (not the patient). **Not PHI.**

---

## 2. Log content review

### 2.1 What structured log lines CAN contain by default

Spring Boot 3.4 structured logging (format: ECS) emits:

- `@timestamp`
- `log.level`
- `log.logger`
- `message` (whatever the application logger passes in)
- MDC entries: `request_id`, `tenant_id`, `user_id`
- Exception details (class, message, stack trace) when thrown

### 2.2 What log lines MUST NOT contain (Rule 01 enforced by test)

`LogPhiLeakageTest` asserts that, during a standard request flow,
log output does NOT contain:

- Bearer token values (substring check — token material is never
  logged by Medcore code; Spring Security redacts the
  `Authorization` header in its own logging).
- Email-like strings (`*@*.*`).
- Caller-supplied `display name` values (emitted as part of JWT
  claims that do not enter Medcore log emission sites).

### 2.3 Log emission sites introduced by this slice

**None.** Phase 3F.1 adds no new `logger.info/warn/error` calls in
application code. The slice adds infrastructure (filters, resolver,
MDC population) that causes EXISTING log emission sites (Spring
framework, Hibernate, Flyway at startup, Medcore existing loggers)
to carry additional MDC context. No new content is introduced into
log messages.

### 2.4 Downstream implications

Phase 3F.2 (OpenTelemetry) will add span emission. Any log/trace
emission added by 3F.2 MUST undergo its own PHI-exposure review and
extend `LogPhiLeakageTest` with coverage for the new emission
sites.

---

## 3. Configuration-driven risks

### 3.1 Mis-configured `MEDCORE_TRUSTED_PROXIES`

**Risk:** An operator configures `0.0.0.0/0` or a too-broad CIDR,
allowing clients to spoof `X-Forwarded-For` and poison audit client
IPs.

**Mitigation:** Default is empty (no XFF trust). Runbook explicitly
warns against permissive values.
`ProxyAwareClientIpResolverTest` covers trusted-list edge cases.
Operator-facing documentation is the primary control; no code-level
defence is feasible because "0.0.0.0/0" is a legitimate value in
some operating contexts.

### 3.2 Mis-configured `MEDCORE_REQUEST_ID_FORMAT`

**Risk:** Operator sets `id-format=^.*$`, accepting any inbound
value including sensitive caller-side data smuggled via the header.

**Mitigation:** Default is strict UUIDv4. Runbook describes safe
alternatives (ULID) and their regex patterns. Max-length cap
defends against unbounded payload regardless of format.

### 3.3 MDC leakage across pooled threads

**Risk:** If a filter's `MDC.put` is not paired with a matching
`MDC.remove` (or the `remove` runs in a non-finally path), a
subsequent request handled by the same Tomcat thread inherits the
old MDC values. Downstream log lines would correlate to the wrong
user or tenant.

**Mitigation:**

- `RequestIdFilter`, `MdcUserIdFilter`, and the modified
  `TenantContextFilter` all clean MDC in a `finally` block.
- `MdcPropagationTest` verifies MDC is clean after a request
  completes (thread-local state asserted).
- Filter ordering is explicit and tested via registration tests.

---

## 4. Conclusion

Phase 3F.1 introduces no new PHI storage, no new log-emission
sites, and no new network exposure. The slice's risk surface is
entirely operational (mis-configuration of proxy trust or id
format), and each operational risk has a documented default, a
runbook warning, and a test where a test is possible.

**Risk: None.** Phase advances safely to implementation.

Carry-forward to Phase 3F.2: extend `LogPhiLeakageTest` with
coverage for any OpenTelemetry span emission added in that slice.
