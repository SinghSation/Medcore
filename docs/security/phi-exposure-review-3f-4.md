# PHI Exposure Review — Phase 3F.4 (Audit Chain Verification Job)

**Slice:** Phase 3F.4 — scheduled invocation of `audit.verify_chain()`;
new `ChainVerifier`, `ChainVerificationScheduler`,
`ChainVerificationScheduleConfig`, `ChainVerificationProperties`; two
new `AuditAction` enum entries (`AUDIT_CHAIN_INTEGRITY_FAILED`,
`AUDIT_CHAIN_VERIFICATION_FAILED`); in-slice doc updates; three new
test suites.

**Reviewer:** Repository owner (solo).

**Date:** 2026-04-22.

**Scope:** All Phase 3F.4 code and configuration changes. The slice
introduces no new PHI surface — it reads the audit chain's integrity
metadata only (sequence numbers, row hashes, prev-hash links). Body
content of audit rows is never emitted to logs or to new audit rows.

**Risk determination:** None. Detailed rationale below.

---

## 1. Data the verifier sees

`audit.verify_chain()` (defined in V9, Phase 3D) walks the chain and
returns zero or more rows with shape:

```
sequence_no BIGINT
reason      TEXT    -- one of: sequence_no_null, sequence_gap,
                    --          first_row_has_prev_hash,
                    --          prev_hash_mismatch, row_hash_mismatch
```

The verifier reads only these two fields. It does NOT read:

- `actor_id` / `tenant_id` / `actor_display` — audit identity fields
- `resource_type` / `resource_id` — audit subject fields
- `request_id` / `client_ip` / `user_agent` — correlation metadata
- `action` / `outcome` / `reason` on audit rows — what-and-why
- `recorded_at` — timestamp

The function's return shape is by design (V9): the verifier reports
integrity state, not row content. Any Medcore caller that wanted to
see row content would need its own SELECT against `audit.audit_event`,
which the verifier does not do.

---

## 2. What the verifier emits on the wire / logs / audit

### Logs

- **Clean case:** a single DEBUG (prod) / INFO (dev opt-in) line
  with message `"audit chain verified: no breaks"`. No row data.
- **Broken case:** a single ERROR line with format:
  ```
  AUDIT CHAIN INTEGRITY FAILURE: <N> break(s) detected, first reason=<code>
  ```
  where `<N>` is an integer count and `<code>` is one of the closed
  set of V9 reason codes listed in §1. No row content, no timestamps,
  no actor / tenant / resource identifiers.
- **Verifier-failed case:** the underlying `SQLException` /
  `DataAccessException` is logged at ERROR via
  `logger.error("chain verifier SQL failure", ex)`. Stack trace and
  exception message are captured. **The exception message could
  contain SQL fragments** (e.g., "permission denied for function
  audit.verify_chain") but does NOT contain audit row data — the
  verifier has not read any row at that point.

### New audit events

Per cycle, at most one event is emitted:

```kotlin
AuditEventCommand(
    action = AUDIT_CHAIN_INTEGRITY_FAILED | AUDIT_CHAIN_VERIFICATION_FAILED,
    actorType = SYSTEM,
    actorId = null,
    tenantId = null,
    resourceType = null,
    resourceId = null,
    outcome = ERROR,
    reason = "breaks:<N>|reason:<code>"  // or "verifier_failed"
    actorDisplay = null,
)
```

Field-by-field PHI analysis:

- `actor_id`, `tenant_id`, `resource_type`, `resource_id`,
  `actor_display` — all null. No inference fuel.
- `action` — closed enum value, opaque slug. Not PHI.
- `outcome` — `ERROR`. Not PHI.
- `reason` — two shapes:
  - `breaks:<N>|reason:<first-code>` where `<N>` is an integer and
    `<first-code>` is one of the closed V9 code set. No row
    content, no user/tenant identifiers, no timestamps.
  - `verifier_failed` — static literal. Not PHI.
- `request_id`, `client_ip`, `user_agent` — lifted from
  `RequestMetadataProvider`, which returns `EMPTY` when no
  request is in scope. Scheduled jobs run outside a request, so all
  three are null on the emitted audit row. This is the correct
  semantic — the event did not originate from a request.

### Response wire surface

None. `ChainVerificationScheduler` is a background job with no HTTP
exposure. The operator surface for manual verification / chain
status is deliberately deferred to Phase 3J.

---

## 3. Read-only enforcement

The verifier performs only:

```sql
SELECT reason FROM audit.verify_chain()
```

No INSERT, UPDATE, DELETE, TRUNCATE, or `rebuild_chain()` is invoked.

This is asserted operationally by `ChainVerifierReadOnlyTest`, which
snapshots every `(id, sequence_no, row_hash)` tuple before and after
verification and asserts byte-for-byte equality. A future edit that
breaks this — e.g., adding a heartbeat INSERT to `audit_event` — will
fail this test and MUST be caught in review.

---

## 4. Attack-surface considerations

### 4.1 Denial-of-audit via repeated break emissions

A broken chain produces MANY break rows from `verify_chain()` (the
chain walk flags every downstream row as broken once the first break
is detected). Emitting one audit event per break row could flood the
audit table and potentially fail the audit write on an already
compromised chain, generating cascading failures.

**Mitigation:** the scheduler emits exactly ONE event per cycle with
a count + first reason. A 1000-row broken chain produces ONE
`integrity_failed` event per hour. Asserted by
`ChainVerificationSchedulerTest.repeated broken outcomes emit one
audit per cycle`.

### 4.2 Infra failure masquerading as chain break

If `verify_chain()` itself cannot run, emitting
`integrity_failed` would incorrectly signal a clinical-safety
incident. Mitigation: the `VerifierFailed` sentinel triggers a
DIFFERENT audit action (`audit.chain.verification_failed`) so a
compliance reviewer can distinguish the two scenarios. Asserted by
`ChainVerificationSchedulerTest.verifier failure emits exactly one
verification_failed audit`.

### 4.3 Log-line exception detail leakage

The verifier's exception log at ERROR captures the `SQLException`
message. Postgres error messages do not typically contain row
content from the table being queried, but a mis-configured
`log_min_error_statement` at the DB level could cause the statement
text to be echoed — the verifier's statement text is only
`SELECT reason FROM audit.verify_chain()`, which contains no
user-supplied input. No PHI path.

### 4.4 Overlapping executions

If a run takes longer than the cron interval, concurrent runs could
emit duplicate audits for the same break set. Mitigation:
`AtomicBoolean.compareAndSet` concurrency guard; overlapping ticks
are skipped with a DEBUG log.

### 4.5 Multi-instance concurrency

If Medcore runs multi-instance (which it does not, yet), every
instance would run the scheduler and multiple `integrity_failed`
events could be emitted for the same break set. Mitigation:
deferred to a future ADR introducing ShedLock or equivalent
distributed locking when multi-instance deployment lands.

---

## 5. Conclusion

Phase 3F.4 introduces no new PHI storage, no new wire surface, and
no new log-emission sites that carry PHI. The audit events emitted
carry only integrity metadata (count + reason code). The verifier
is provably read-only. The attack-surface considerations in §4 all
have documented mitigations.

**Risk: None.**
