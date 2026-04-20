# ADR-003: Audit v1 is append-only, synchronous, immutable by DB grants; cryptographic integrity (v2) deferred to Phase 3D

- **Status:** Proposed
- **Date:** 2026-04-20
- **Authors:** Gurinder Singh
- **Reviewers:** Gurinder Singh (repository owner)
- **Supersedes:** none
- **Related:** ADR-001 (persistence), ADR-002 (identity),
  `.cursor/rules/06-audit-observability.mdc`, `AGENTS.md` §§3.6, 4.4,
  `.claude/skills/phi-exposure-review.md`

---

## 1. Context

`AGENTS.md` §3.6 and Rule 06 require append-only, tamper-evident audit
behavior for regulated access and system actions. Audit events must be
append-only and tamper-evident, writes must be synchronous with the
audited action, and audit records must never contain PHI payloads.

Phase 3 begins by auditing identity and tenancy events before any
PHI-bearing modules exist. These actions — login, provisioning,
tenant-context selection, membership reads — are not themselves PHI
access, but they are the actor-resource-outcome records a compliance
auditor uses to reconstruct who did what. They must be captured from the
moment the corresponding code exists, and the same append-only,
synchronous, immutable-by-grant substrate that will eventually hold PHI
access events must hold them too.

A full-strength audit design includes both immutability **by DB
permissions** (no `UPDATE`/`DELETE` grant for the application role) and
**cryptographic tamper-evidence** (sequence numbers, previous-row hashes,
verifiable chain). Shipping both at once in Phase 3C would couple the
first audit implementation to a hash-chain writer with advisory locking,
which complicates a path that has not yet been proven. A two-pass
approach lands the non-negotiable guarantees now and the cryptographic
guarantees in Phase 3D.

## 2. Decision

**We will ship Audit v1 in Phase 3C: an append-only `audit.audit_event`
table, synchronous writes inside the caller's transaction, immutability
enforced by DB grants, and strongly-typed input to prevent PHI leakage.
Cryptographic integrity (`sequence_no`, `prev_hash`, `row_hash`, and
chain verification) is deferred to Phase 3D (Audit v2) under a follow-up
ADR.**

Specifics of **Audit v1**:

- Table `audit.audit_event` with the v1 column set defined in the Phase 3
  plan: `id`, `recorded_at`, `tenant_id` (nullable), `actor_type`,
  `actor_id`, `actor_display`, `action`, `resource_type`, `resource_id`,
  `outcome`, `request_id`, `client_ip`, `user_agent`, `reason`.
- Writes are synchronous, inside the caller's `@Transactional` scope. A
  failure to write audit fails the audited action.
- The `medcore_app` DB role is granted `INSERT` and `SELECT` on
  `audit.audit_event`; `UPDATE` and `DELETE` are revoked. Schema owner
  and DDL remain with `medcore_migrator`.
- `AuditWriter` accepts a strongly-typed `AuditEventCommand` — no
  free-form `details: Map<String, Any>` that could accept PHI by
  accident.
- Tests assert: exactly one audit row per audited action, with the
  expected field values; attempts to `UPDATE` or `DELETE` from the app
  role fail at the DB layer.
- Indexes: BRIN on `recorded_at`; B-tree on `(tenant_id, recorded_at)`.

Specifics of **Audit v2** (Phase 3D):

- Add `sequence_no BIGINT NOT NULL`, `prev_hash BYTEA`, `row_hash
  BYTEA NOT NULL` via a Flyway migration.
- `AuditWriter` acquires `pg_advisory_xact_lock` keyed on tenant (or a
  global key for cross-tenant events) to serialize chain inserts.
- `row_hash = SHA-256(canonical(row) ‖ prev_hash)`.
- A scheduled verification job (or CLI) walks the chain, recomputes
  hashes, and fails an integration test / emits an alertable metric on
  mismatch.
- Backfill of existing v1 rows happens under the advisory lock in the
  same migration.
- Covered by its own ADR (candidate number ADR-005) when Phase 3D opens.

## 3. Alternatives Considered

### Ship hash chain immediately (Phase 3C)
Stronger tamper-evidence on day one. Rejected for Phase 3C because it
adds advisory-locking logic, hash canonicalisation, and backfill scaffolding
to the first working audit pipeline. Phase 3C remains simpler; Phase 3D
adds the integrity layer after v1 correctness is proven. Our governance
is not weakened: an attacker with `medcore_app` credentials still cannot
modify audit rows — DB grants block them.

### Fire-and-forget (queue, async worker, or application event)
Prohibited by Rule 06 ("Audit writes MUST be synchronous with the action
being audited"). Not considered further.

### Audit outside the database (external audit service)
Adds egress, cost, and network-failure modes. Rejected for Phase 3. May
be reconsidered at a later scale point with a superseding ADR.

### Free-form `details: JSONB` column
Flexible but trivially accepts PHI by accident. Rejected. The typed
command shape forces PR reviewers to notice any new field and class it.

### Log-only audit (structured logs to a file or log sink)
Insufficient because logs are not transactional with the action, may be
dropped, and are harder to prove tamper-evident. Logs continue to exist
for observability purposes but are not the audit store.

## 4. Consequences

### 4.1 Positive
- Phase 3C lands a correct, enforceable audit surface quickly.
- Immutability is **enforced by Postgres**, not by convention.
- Typed input makes PHI-leakage via audit essentially impossible without
  a schema change that would be noticed in review.
- Phase 3D can add cryptographic evidence without rewriting v1 semantics.
- Identity and tenancy actions are audited from the moment they exist,
  so the timeline is complete when PHI-bearing modules arrive.

### 4.2 Negative
- Between Phase 3C and Phase 3D, the audit store is tamper-evident against
  an application-role attacker only. A DB superuser with malicious intent
  could still alter data; this is a smaller residual threat mitigated by
  operational controls (role separation, access reviews), but it is real
  until v2.
- The v1 → v2 migration requires a backfill under an advisory lock. At
  Phase 3D volumes this is trivial; at higher volumes the migration is
  more careful.
- Audit retention is unbounded at v1. Retention is out of scope for this
  ADR and will be set by a separate retention ADR.

### 4.3 Risks & Mitigations
- **PHI leaking into audit fields.** Mitigation: typed command shape,
  field-level naming review, and the `phi-exposure-review` skill run on
  every PR that touches the audit pipeline.
- **Audit write failure silently loses events.** Mitigation: synchronous,
  same-transaction writes. A failed audit insert fails the audited action.
- **Accidental `UPDATE/DELETE` grant on `audit.audit_event`.** Mitigation:
  a negative integration test that connects as the app role and asserts
  `UPDATE`/`DELETE` fail with a permission error.
- **Wrong `tenant_id` on an audit event.** Mitigation: `tenant_id` is
  sourced from the request-scoped `TenantContext`, not from caller-passed
  arguments; cross-tenant events use an explicit null and a documented
  `action` prefix.
- **v1 → v2 migration breaks chain correctness.** Mitigation: the v2
  migration is itself covered by a chain-integrity test; backfill is
  idempotent and runs under `pg_advisory_xact_lock`.

## 5. Compliance & Security Impact

- **HIPAA 45 CFR §164.312(b)** (audit controls): satisfied at v1 by
  immutability-by-grant and synchronous write semantics; strengthened in
  v2 by cryptographic integrity.
- **SOC 2 CC7.2 / CC7.3** (system monitoring, anomalies): the audit store
  is the primary source of truth for these controls.
- **HIPAA minimum-necessary (§164.502(b)):** audit records describe that
  access occurred, not the payload; typed command enforces this.
- **Retention:** not covered here. Retention horizon for `audit.audit_event`
  will be defined in `docs/compliance/retention.md` under a separate ADR.

## 6. Operational Impact

- **Storage growth:** audit table grows unbounded until a retention policy
  lands. At Phase 3 volumes, negligible.
- **Query cost:** BRIN on `recorded_at` keeps hot-path INSERT cost low;
  B-tree on `(tenant_id, recorded_at)` supports tenant-scoped timelines.
- **Roles:** `medcore_migrator` owns DDL; `medcore_app` does DML with
  audit-specific restrictions. Role split lands with Phase 3C step 18 in
  the plan.
- **Migration v1 → v2:** one-shot, reversible in the sense that the new
  columns can be dropped; historical rows cannot be un-chained once
  hashed, but the chain is additive to v1 data.

## 7. Rollout Plan

1. Accept this ADR.
2. Phase 3C creates `audit.audit_event` via `audit/V001__audit_event.sql`.
3. Role split: `medcore_migrator` owns schema/DDL; `medcore_app` granted
   `INSERT, SELECT` on `audit.audit_event`, `UPDATE/DELETE` revoked.
4. Implement `AuditWriter` with typed `AuditEventCommand`. Synchronous;
   uses the caller's transaction.
5. Hook identity actions (`identity.user.login.success`,
   `identity.user.login.failure`, `identity.user.provisioned`).
6. Hook tenancy actions (`tenancy.context.set`, `tenancy.membership.list`,
   `tenancy.membership.denied`).
7. Integration test: attempting `UPDATE` or `DELETE` on `audit.audit_event`
   from the app role fails at the DB layer.
8. Phase 3D opens an ADR (candidate ADR-005) for Audit v2 and executes
   the `sequence_no` / `prev_hash` / `row_hash` migration with chain
   backfill and verification.

**Rollback plan:**
- v1 rollback (Phase 3C): `DROP TABLE audit.audit_event` via a new Flyway
  migration. Acceptable only because no PHI has been audited at Phase 3C.
- v1 → v2 rollback (Phase 3D): drop the v2 columns. v1 readers still
  function. No data loss; cryptographic guarantees are forfeited until
  v2 re-lands.
- Exit from having an audit store: **not an option.** `AGENTS.md` and
  governing regulation both require audit.

## 8. Acceptance Criteria

- [ ] `audit.audit_event` v1 migration lands under `audit/V001__…`.
- [ ] `medcore_app` role has `INSERT, SELECT` only on `audit.audit_event`.
- [ ] `AuditWriter` service implemented with typed `AuditEventCommand`.
- [ ] Identity hooks fire `identity.user.login.success`,
      `identity.user.login.failure`, `identity.user.provisioned`.
- [ ] Tenancy hooks fire `tenancy.context.set`,
      `tenancy.membership.list`, `tenancy.membership.denied`.
- [ ] Negative integration test proves app role cannot `UPDATE/DELETE`.
- [ ] PHI-exposure review (`.claude/skills/phi-exposure-review.md`)
      attached to the PR that lands `AuditWriter`.
- [ ] Phase 3D tracking issue opened referencing this ADR for v2.

## 9. References

- `AGENTS.md` §§3.6, 4.4
- `.cursor/rules/06-audit-observability.mdc`
- `.claude/skills/phi-exposure-review.md`
- 45 CFR §164.312(b) — HIPAA audit controls
- SOC 2 Trust Services Criteria — CC7.2, CC7.3
- PostgreSQL documentation — advisory locks, `REVOKE`, BRIN indexes
