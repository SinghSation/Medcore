# PHI Exposure Review — Phase 3G (Error Standardization)

**Slice:** Phase 3G — unified `ErrorResponse` envelope; new
`MedcoreAuthenticationEntryPoint`, `MedcoreAccessDeniedHandler`,
`GlobalExceptionHandler`; in-slice doc updates and test-only
controller.

**Reviewer:** Repository owner (solo).

**Date:** 2026-04-22.

**Scope:** All Phase 3G code and configuration changes. No clinical
PHI is handled by this slice — the surface is pure error-response
envelope standardization. The review covers error-response bodies,
error-response headers, the test-only `ErrorPathsTestController`,
and the interaction between the new 403 path and existing audit
emission.

**Risk determination:** None. Detailed rationale below.

---

## 1. What this slice emits to the wire

Every 4xx / 5xx response from the backend:

```json
{
  "code": "<stable-slug>",
  "message": "<fixed-non-user-controlled-string>",
  "requestId": "<uuid-from-MDC>",
  "details": null | {"validationErrors": [{"field": "...", "code": "..."}, ...]}
}
```

Field-by-field PHI analysis:

- **`code`** — closed enum of stable slugs (`auth.unauthenticated`,
  `auth.forbidden`, `tenancy.forbidden`, `resource.not_found`,
  `resource.conflict`, `request.validation_failed`,
  `tenancy.context.required`, `server.error`). Opaque to clients.
  Not PHI.
- **`message`** — fixed, compile-time constant strings declared in
  the emitting handler. Never constructed from exception messages,
  user input, or DB column values. `GlobalExceptionHandler`'s
  `onUncaught` deliberately returns `"An unexpected error occurred."`
  regardless of the underlying `Throwable`. Not PHI.
- **`requestId`** — UUIDv4 from MDC (Phase 3F.1). Correlation
  identifier only. Not PHI.
- **`details.validationErrors[].field`** — the name of the field
  that failed validation (e.g., `"name"`, `"amount"`). Field NAMES
  are not PHI. **Field VALUES are never echoed** — the 3G plan
  explicitly forbids `rejectedValue` in the payload because a
  rejected patient DOB or MRN value would leak PHI directly.
  `GlobalExceptionHandlerIntegrationTest` asserts `{field, code}`
  only.
- **`details.validationErrors[].code`** — the constraint-annotation
  short name Spring produces (`NotBlank`, `Size`, `Min`, etc.).
  Framework-level metadata. Not PHI.
- **`WWW-Authenticate` header on 401** — set to the minimal
  `Bearer` challenge. Deliberately NOT the richer form Spring's
  default entry point emits (`Bearer realm="..." error="..."
  error_description="..."`) because the `error_description` can
  echo exception detail. Not PHI; no enumeration.

---

## 2. Leakage paths considered and tested

### 2.1 Stack traces

`GlobalExceptionHandler.onUncaught` logs the full stack trace at
ERROR level (with `requestId` in MDC for correlation) but never
includes it in the response body. `ErrorResponsePhiLeakageTest`
asserts `"at com.medcore"`, `"at org.springframework"`,
`"at java.base"`, and `"Caused by"` are absent from every tested
error body.

### 2.2 Framework exception class names

`ErrorResponsePhiLeakageTest` fires endpoints that throw
`IllegalStateException`, `EntityNotFoundException`,
`OptimisticLockingFailureException`, `MethodArgumentNotValidException`,
etc., and asserts no occurrence of those class names in the response
body.

### 2.3 SQL fragments

The test-only controller throws `IllegalStateException` with the
message `"test-only: something-internal SELECT * FROM patients
WHERE id=123"`. Test asserts `"SELECT"`, `"FROM patients"`, and
`"id=123"` are absent from the 500 body. This proves the fallback
handler is genuinely opaque — a real service error that happened
to echo SQL in its message could not leak.

### 2.4 Bearer token values

401 path tested with `Authorization: Bearer tampered-<token>`.
Test asserts neither the token nor the `"tampered-"` prefix
appears in the response body.

### 2.5 Rejected field values

422 body-validation path POSTs `{"name":"patient-dob-1970-01-01"}`
— a PHI-shaped value that MUST NOT be echoed. Test asserts the
value is absent from the response body. Only the FIELD NAME
`"name"` is allowed to appear (and asserted to appear, since the
validation rejected it).

### 2.6 Enumeration signals

`auth.forbidden` and `tenancy.forbidden` both emit
`"Access denied."` — identical text. A caller probing for routes
cannot distinguish an unauthorized path from a tenancy-scoped one
without actually authorizing. Enforced in
`GlobalExceptionHandlerIntegrationTest`.

---

## 3. Test-only controller — separately analysed

`ErrorPathsTestController` is mounted only when
`medcore.testing.error-paths-controller.enabled=true` is explicitly
set (default false). The `@ConditionalOnProperty
matchIfMissing=false` means absence of the property is a hard
no-mount.

Additional hardening:

- Class lives under `src/test/kotlin`, which is excluded from the
  production jar by standard Gradle test-source conventions.
- Handler bodies contain no real data — only compile-time-constant
  throw-clauses.
- Seeded "suspicious" text (`"something-internal SELECT * FROM
  patients WHERE id=123"`) exists solely as a negative-case fixture
  for `ErrorResponsePhiLeakageTest`; it never reaches any
  production code path.

Result: no risk of the test controller shipping to any real
environment.

---

## 4. Interaction with existing audit emission

- `AuditingAuthenticationEntryPoint` continues to emit
  `identity.user.login.failure` exactly as before (when an
  `Authorization: Bearer ...` header was present). Only the
  terminal 401 body format changed; the audit side is verbatim.
  Asserted by the existing `AuditIdentityIntegrationTest` that
  remains green (it checks status and audit row, not body shape).
- `MedcoreAccessDeniedHandler` does NOT emit audit — deliberate
  Phase 3G non-goal. Authorization-denial audit lands in Phase 3J
  with RBAC. The carry-forward is tracked.

---

## 5. Residual concerns and follow-ups

- **400 Bad Request paths remain Spring-default.** A malformed JSON
  body today produces a Spring-shaped response (not our envelope).
  Not a PHI risk — Spring's default 400 body does not echo request
  bodies — but it IS a consistency gap. Tracked as carry-forward.
- **`details` discipline depends on handler-writer hygiene.** The
  rule "field names only, never values" lives in
  `ErrorResponse.kt`'s KDoc and in `03-definition-of-done.md` §3.2.
  A future handler that populated `details` with a rejected value
  would leak. No mechanical enforcement today; CI (Phase 3I) can
  add a test-asserted invariant.

---

## 6. Conclusion

Phase 3G introduces no new PHI storage, no new log-emission sites
(logs were already in place in 3F.1), and no weakening of existing
controls. The slice tightens the response-side discipline (unified
envelope, fixed messages, leakage tests).

**Risk: None.**
