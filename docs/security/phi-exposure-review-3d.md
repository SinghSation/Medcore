# PHI Exposure Review — Phase 3D (RLS + Audit v2)

Performed against the working tree for the Phase 3D slice prior to
commit. Follows the procedure in
`.claude/skills/phi-exposure-review.md`.

## 1. Scope of the diff

**New files (8):**

- `apps/api/src/main/resources/db/migration/tenancy/V8__tenancy_rls.sql`
- `apps/api/src/main/resources/db/migration/audit/V9__audit_event_v2.sql`
- `apps/api/src/main/kotlin/com/medcore/platform/persistence/TenancySessionContext.kt`
- `apps/api/src/test/kotlin/com/medcore/tenancy/rls/TenancyRlsTest.kt`
- `apps/api/src/test/kotlin/com/medcore/tenancy/rls/TenancyGucLifecycleTest.kt`
- `apps/api/src/test/kotlin/com/medcore/audit/v2/AuditV2ChainTest.kt`
- `apps/api/src/test/kotlin/com/medcore/audit/v2/AuditV2BackfillTest.kt`
- `docs/security/phi-exposure-review-3d.md` (this file)

**Modified:**

- `apps/api/src/main/kotlin/com/medcore/platform/audit/JdbcAuditWriter.kt`
- `apps/api/src/main/kotlin/com/medcore/tenancy/service/TenancyService.kt`
- `apps/api/src/test/kotlin/com/medcore/MedcoreApiApplicationTests.kt`
- `apps/api/src/test/kotlin/com/medcore/audit/AuditImmutabilityTest.kt`
- `docs/runbooks/local-services.md`

**Summary.** Phase 3D enables PostgreSQL RLS on the tenancy tables
and upgrades `audit.audit_event` in place to a cryptographic hash
chain (Audit v2). No new module surfaces user content; no controller
or DTO gains any new field. Canonicalisation + hashing live
exclusively in Postgres functions so the Kotlin writer no longer
handles hash material.

**Modules affected:** `platform/audit`, `platform/persistence`,
`tenancy/service`, plus new audit migration and tests.

## 2. Data inventory

Every field that can now flow into the new `audit.audit_event` v2
columns:

| Field          | Source                               | Destination       | Classification | Tagged?    |
| -------------- | ------------------------------------ | ----------------- | -------------- | ---------- |
| `sequence_no`  | Postgres function (monotonic counter)| audit.audit_event | Internal       | n/a        |
| `prev_hash`    | SHA-256 bytes (of prior row)         | audit.audit_event | Internal       | n/a        |
| `row_hash`     | SHA-256 bytes (of current row)       | audit.audit_event | Internal       | n/a        |

The canonicalisation input per row is exactly the existing v1 column
set: `id`, `recorded_at`, `tenant_id`, `actor_type`, `actor_id`,
`actor_display`, `action`, `resource_type`, `resource_id`, `outcome`,
`request_id`, `client_ip`, `user_agent`, `reason`. Every one of
these was already on the table in 3C and was reviewed under that
slice's PHI exposure review (see `phi-exposure-review-3c.md`, §2).
No PHI field was added in this slice, and the hash is a deterministic
function of fields that were already non-PHI by construction.

Hash bytes are not reversible to plaintext. Even if a row is somehow
exfiltrated in the future, the hash reveals nothing beyond "this
exact canonical string existed". No PHI by the definition in
`AGENTS.md` §5.

**GUCs set by `TenancySessionContext`:**

| GUC                      | Source                  | Destination          | Classification |
| ------------------------ | ----------------------- | -------------------- | -------------- |
| `app.current_user_id`    | `MedcorePrincipal.userId` | Postgres session GUC | Internal     |
| `app.current_tenant_id`  | `TenantContext`.resolved | Postgres session GUC | Internal     |

Both are opaque Medcore-internal UUIDs — same as `identity.user.id`
and `tenancy.tenant.id` elsewhere. Not PII, not PHI.

## 3. Exposure checklist

- [x] **No** — write PHI to a structured log? The writer still logs
      nothing at the application layer; audit emission goes direct to
      DB via `audit.append_event`.
- [x] **No** — PHI in an error message or exception? Migration
      exceptions, RLS denial exceptions, and verify_chain's
      reason-code column all use server-side typed codes
      (`row_hash_mismatch`, etc.), never user-supplied values.
- [x] **No** — PHI in metric labels, spans, or traces? No new
      metrics or spans added.
- [x] **No** — analytics, session-replay, telemetry sinks? None added.
- [x] **No** — PHI in URL, query string, or path? Only change to
      path surface is a runbook example. No route changes.
- [x] **No** — PHI to `localStorage`, `sessionStorage`, `IndexedDB`,
      cookies, non-encrypted caches? No client-side changes.
- [x] **No** — PHI in an email, webhook, outbound integration? None.
- [x] **No** — PHI in a build artifact or generated file? None.
- [x] **No** — PHI into an AI prompt, embedding, or model log? None.
- [x] **No** — expands the set of roles / tenants that can read the
      data? The opposite — RLS *restricts* what `medcore_app` can
      see on tenancy tables. Superusers still bypass (local dev
      only); documented.
- [x] **No** — exports PHI to a file, CSV, PDF, download? None.
- [x] **No** — fixtures, seeds, or README examples with real PHI?
      All test data is synthetic (`subject = "alice"`, fabricated
      emails at `@medcore.test`, fabricated slugs). Already covered
      by prior phases' policy.

## 4. Redaction verification

- No application-layer logger is added or reconfigured.
- The hash pipeline has no log sink. The writer's exceptions surface
  to Spring's error paths unchanged from 3C.
- RLS denials happen inside Postgres — the app sees zero rows with
  no "denied" telemetry at all (service-layer checks continue to
  emit `tenancy.membership.denied` at the structured-audit layer
  from 3C, which is PHI-free by construction).

## 5. Access-control verification

- **RLS strengthens, not weakens, access control — when the runtime
  role switch is in place.** Pre-V8, the `medcore_app` role had no
  SELECT grants on tenancy tables at all. Post-V8, it has SELECT but
  only returns rows that match its policies. Net effect for any
  non-superuser caller: tighter than before.
- **Runtime enforcement is contingent on a deferred ops slice.**
  RLS correctness is proven against `medcore_app` (`TenancyRlsTest`
  connects directly as that role). The running application,
  however, still uses the container superuser datasource in local
  dev. Postgres superusers bypass RLS by design (FORCE ROW LEVEL
  SECURITY does not bind superusers; that flag binds table owners).
  Therefore Phase 3D delivers the RLS *substrate*, not the runtime
  hard gate. The hard gate becomes effective when a separate Tier 3
  ops slice splits the datasource (Flyway/migrator vs. application
  JPA) and flips the application connection to `medcore_app` with a
  secret-manager-supplied password. That work is a stated
  prerequisite for any Phase 4 / PHI-bearing module under ADR-001
  §2 and is tracked as the top-priority carry-forward item below.
- **Audit v2 preserves v1 grants.** `medcore_app` still has INSERT
  and SELECT on `audit.audit_event`, still has no UPDATE / DELETE /
  TRUNCATE. `AuditImmutabilityTest` continues to assert those
  denials. V9 adds `EXECUTE` on `audit.append_event` — the function
  is `SECURITY INVOKER`, so the caller's own grants govern its
  INSERTs. `audit.rebuild_chain` requires UPDATE and is therefore
  *not* callable by `medcore_app`; only the migrator / superuser can
  rebuild the chain.

## 6. AI-specific checks

Not applicable; no AI integration in this slice.

## 7. Tests

Each anti-leakage and integrity claim is backed by at least one test:

- **RLS enforcement:** `TenancyRlsTest` — authorized read,
  cross-user isolation, missing-GUC fail-closed, SUSPENDED
  membership does not reveal tenant.
- **GUC lifecycle:** `TenancyGucLifecycleTest` — apply outside tx
  rejected, apply inside tx sets both GUCs, no leakage across
  transactions on the same pooled connection, null userId yields
  fail-closed NULL.
- **Chain integrity:** `AuditV2ChainTest` — contiguous sequence,
  healthy chain verifies clean, tampering with `reason` detected,
  tampering with `prev_hash` detected, sequence_no uniqueness
  enforced.
- **Backfill correctness:** `AuditV2BackfillTest` — legacy
  v1-shaped rows are re-chained correctly by `audit.rebuild_chain`.
- **Preserved invariants:** `AuditImmutabilityTest` (updated to use
  `append_event` but still asserts UPDATE/DELETE/TRUNCATE denial);
  `AuditTransactionAtomicityTest` (unchanged — atomicity of a
  poisoned write still rolls back identity).

## 8. Risk summary

**None.** No PHI surface exists in Medcore today, and Phase 3D adds
no new surfaces. RLS + chain are defense-in-depth over fields that
were already classified and reviewed.

## 9. Sign-off

- Author (assistant): review performed on the final 3D diff in the
  current session, prior to commit.
- Reviewer: requires human review against the diff before commit;
  this artifact is the input to that review.
