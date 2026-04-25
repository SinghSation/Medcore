# ADR-009: List pagination substrate (cursor-based, opaque cursor)

- **Status:** Accepted
- **Date:** 2026-04-25
- **Authors:** Gurinder Singh
- **Reviewers:** Gurinder Singh (repository owner)
- **Supersedes:** none
- **Related:** ADR-001 (Postgres + RLS), ADR-003 (audit append-only), ADR-007 (WriteGate)

---

## 1. Context

Four list endpoints have shipped without pagination, each with the
same comment "Adding pagination is additive in a later slice":

- `GET /api/v1/tenants/{slug}/encounters/{encounterId}/notes` (4D.1)
- `GET /api/v1/tenants/{slug}/patients/{patientId}/encounters` (4C.3)
- `GET /api/v1/tenants/{slug}/patients/{patientId}/allergies` (4E.1)
- `GET /api/v1/tenants/{slug}/patients/{patientId}/problems` (4E.2)

CodeRabbit flagged 4E.2 as a coding-guideline violation
(`.cursor/rules/02-api-contracts.mdc`: "List endpoints MUST be
paginated with default and maximum page sizes declared in the
schema"). Fixing only one endpoint introduces per-domain divergence
across identical-shape APIs. Fixing all four requires a substrate
decision first — without one, each domain would invent its own
envelope, cursor format, and audit slug.

**Decision driver:** Pagination is a cross-cutting platform concern,
not a per-domain feature. It must be applied uniformly so future
list endpoints (medications, vitals, problems-history, etc.)
inherit the substrate by default and a regression — adding an
unpaginated list endpoint — fails at the architecture-test layer,
not at code review.

## 2. Decision

**We will introduce a single `read.pagination` substrate that all
four existing list endpoints adopt in this slice, and that any
future list endpoint MUST adopt by ArchUnit rule.**

### 2.1 Cursor-based, not offset-based

Cursor pagination is correct for append-mostly clinical tables:

- Stable under concurrent writes — a row inserted between page 1
  and page 2 doesn't shift offsets and produce duplicates / skips.
- No `OFFSET N` performance cliff at scale (PG must scan + discard
  N rows for every paginated request).
- Aligns with FHIR R4 Bundle pagination convention (relation type
  `next`, opaque continuation token) for the future 5A FHIR slice.

Offset pagination considered and rejected: simpler clients, but the
correctness cost under writes is unacceptable for a clinical record
system where "page 2 missing the row that was on page 1" is a
disclosure-integrity failure.

### 2.2 Opaque base64-encoded JSON cursor

The cursor is a JSON object, base64-encoded, treated by clients as
opaque. The server is the sole authority over encoding; clients
MUST NOT parse, construct, or compose cursor tokens.

The JSON object's shape varies per resource (different sort axes —
see §2.5) but always carries:

- `k`: a resource-version discriminator (e.g., `"clinical.problem.v1"`)
  — lets future schema evolution coexist with mid-pagination clients.
- The full sort tuple of the **last row** in the previous page.
  For problems / allergies this is `(status_priority, ts, id)`.
  For encounters / encounter-notes this is `(ts, id)`.
- An `id` tie-breaker (always present) so equal-timestamp rows
  paginate deterministically.

**Why the cursor must encode the FULL sort tuple, not just the last
value + id**: composite sort keys (e.g., problems' `(status_priority,
createdAt DESC)`) require the cursor to encode every component.
Encoding only `(createdAt, id)` would break across status buckets:
the server can't tell which status bucket the cursor's `ts` belongs
to and can't construct the correct `WHERE` clause to advance past it.

### 2.3 Default 50, maximum 100

`pageSize` query parameter:

- Default: **50** rows
- Minimum: **1** row
- Maximum: **100** rows
- Out of range: 422 `pageSize|out_of_range`

50 fits the typical chart load (5–20 rows) in a single round-trip
while keeping the response body bounded for adversarial / research-
import patient histories. 100 is the absolute ceiling — clients
needing deeper history paginate.

### 2.4 Response envelope: `{ items, pageInfo }` — NO `totalCount`

Every list response carries:

```json
{
  "data": {
    "items": [ ... ],
    "pageInfo": {
      "hasNextPage": true,
      "nextCursor": "eyJrIjoiY2xpbmljYWwucHJvYmxlbS52MSIsInNwIjowL..."
    }
  },
  "requestId": "..."
}
```

**`totalCount` is deliberately omitted.** Computing total count
requires a redundant `COUNT(*)` query per paginated call; clinically
not load-bearing for chart context (clinicians don't need "47 of
312" — they need "the active rows are these"). FHIR Bundle
pagination follows the same posture (total is optional; `link
rel="next"` is the contract).

`hasNextPage = false` AND `nextCursor = null` indicates the last
page. Clients MUST stop on that signal; calling with a stale cursor
when `hasNextPage` was false yields an empty page (no error).

### 2.5 Sort key per endpoint (NORMATIVE)

Each list endpoint locks a stable sort axis. The cursor encodes
the corresponding tuple.

| Endpoint | Sort axis | Cursor tuple | Index requirement |
|---|---|---|---|
| problems | `(status_priority, createdAt DESC, id)` | `(sp, ts, id)` | `(tenant_id, patient_id, status, created_at, id)` |
| allergies | `(status_priority, createdAt DESC, id)` | `(sp, ts, id)` | `(tenant_id, patient_id, status, created_at, id)` |
| encounters | `(createdAt DESC, id)` | `(ts, id, asc=false)` | `(tenant_id, patient_id, created_at, id)` |
| encounter-notes | `(createdAt DESC, id)` | `(ts, id, asc=false)` | `(tenant_id, encounter_id, created_at, id)` |

**`status_priority` mapping (NORMATIVE)**:

| Status | Priority |
|---|---|
| `ACTIVE` | 0 |
| `INACTIVE` | 1 |
| `RESOLVED` | 2 |
| `ENTERED_IN_ERROR` | 3 |

This mapping is load-bearing for the `RESOLVED ≠ INACTIVE`
invariant established in 4E.2. Clinicians must always see ACTIVE
rows in the first page (priority 0 sorts first); the cards on
patient detail render only the first page, so a 50-row first page
will contain every ACTIVE row up to 50 — adequate by a wide margin
for chart context. The ordering is encoded in:

- The DB query's `CASE WHEN status='ACTIVE' THEN 0 ...` clause.
- A Kotlin extension `ProblemStatus.priority` / `AllergyStatus.priority`
  that constructs the same integers.
- The cursor's `sp` field.

A change that drifts the priority mapping fails at three layers
(DB query, Kotlin extension, cursor encoding) — same multi-layer
discipline as the other clinical invariants.

### 2.6 ArchUnit Rule 15 — no unpaginated lists ever again

A new ArchUnit rule lands in this slice (chunk B, alongside the
first endpoint adoption): any controller method whose return type
contains `*ListResponse` MUST take a `PageRequest` parameter.
Equivalent enforcement for the read-handler layer ensures the
substrate is threaded end-to-end.

This is the same registry-discipline pattern as `AuditAction`,
`ErrorCodes`, and the `MedcoreAuthority` enum: a structural test
that prevents future regressions, not a code-review checklist.

### 2.7 Audit slug change (NORMATIVE)

The four `*_LIST_ACCESSED` audit actions currently emit:

```
intent:<resource>.list|count:N
```

where `N` is the total disclosed-rows count. Per-page pagination
changes the meaning of `count`:

```
intent:<resource>.list|count:N|page_size:P|has_next:bool
```

- `count` now means "rows disclosed in THIS page" (not total).
- `page_size` is the requested page size.
- `has_next` lets forensic queries identify multi-page disclosures
  without joining structured logs.

**This is a semantic change. The ADR documents it explicitly so
audit consumers (compliance dashboards, forensic queries) can
adapt.** The closed-enum slug shape remains; only the integer's
meaning evolves. Tests that previously asserted
`count:N == totalRows` need to assert per-page count and a
deterministic `has_next` value.

### 2.8 Backward compatibility — break the wire envelope intentionally

The four list endpoints' wire envelopes change shape from
`{ items: [...] }` to `{ items: [...], pageInfo: {...} }`. This is
**not backward-compatible** for any consumer that constructs a
type-strict response object on the existing envelope.

**The decision to break compatibility is intentional**, justified
by:

- Pre-1.0 system; no external consumers.
- The frontend is the only consumer of all four endpoints; it
  updates atomically in this slice.
- A non-breaking alternative (e.g., add `pageInfo` only when
  `pageSize` query param is present) would create two response
  shapes for the same endpoint — a worse design choice that
  multiplies test surface and confuses future contributors.
- FHIR alignment: when the 5A FHIR Bundle slice lands, the
  internal envelope already matches the Bundle pagination shape.

Calls without `pageSize` / `cursor` query params behave as
`pageSize=50, cursor=null` (first page, default size). The wire
envelope still includes `pageInfo` on those calls.

### 2.9 Frontend behavior — first page only in MVP

Cards (e.g., `ProblemsCard`, `AllergyBanner`, encounter list)
render the first page only. With `status_priority` ordering, ACTIVE
rows always land in the first page (assuming the patient has fewer
than 50 ACTIVE rows of any given resource — well above clinical
norm). Management modals likewise show the first page; a future
"history" surface will introduce per-page navigation when a real
research / longitudinal-export workflow demands it.

No "load more" button, no infinite scroll, no cursor handling in
the UI in this slice. Frontend complexity stays minimal; the
substrate is in place for when the UX requires deeper history.

## 3. Consequences

### 3.1 Wins

- All four list endpoints satisfy `.cursor/rules/04` rule.
- ArchUnit Rule 15 prevents future regression structurally.
- Single envelope, single cursor format, single default/max page
  size — no per-domain divergence.
- FHIR Bundle alignment is now structural, not aspirational.
- Forensic audit has explicit per-page semantics; multi-page
  disclosures are identifiable from a single audit row's
  `has_next` field.

### 3.2 Costs

- Wire envelope breaking change. Frontend updated atomically; no
  external consumers exist. Documented in §2.8.
- Audit semantics shift: `count` is now per-page, not total.
  Documented in §2.7. Existing tests (4E.1, 4E.2) need updates;
  no production audit consumers exist yet.
- Each endpoint's repository query gains a cursor-aware branch;
  the JPQL composes a `WHERE (sp > :sp_last) OR (sp = :sp_last
  AND createdAt < :ts_last) OR ...` predicate.
- New DB index per table (one per chunk B–E migration).

### 3.3 Risks (and mitigations)

- **Risk:** A future endpoint adopts a sort axis the substrate's
  helper cursors don't model.
  **Mitigation:** `Cursor` is an interface; resources can define
  bespoke implementations. The substrate provides `BucketedCursor`
  + `TimeCursor` as ergonomic defaults, not a closed taxonomy.

- **Risk:** Mid-pagination cursor decoded by a server with a
  newer schema misinterprets the tuple.
  **Mitigation:** The `k` discriminator (e.g., `"clinical.problem.v1"`)
  versions the cursor format. A v2 server seeing v1 cursors can
  either accept them (compatibility) or reject them with
  422 `cursor|stale_format`. Stale-cursor rejection is forgiving
  (clients refetch from page 1).

- **Risk:** A clinician with > 50 ACTIVE rows of a single resource
  doesn't see all of them on the card.
  **Mitigation:** Clinically implausible at chart-context scope —
  no patient has >50 currently-active problems, allergies, or
  in-progress encounters. The management modal also renders the
  first page only in MVP; if a real workflow surfaces this need,
  the modal can adopt cursor-driven "load more" without changing
  the substrate. Documented as a known MVP boundary.

## 4. Alternatives considered

- **Offset pagination** — rejected for correctness reasons under
  concurrent writes (§2.1).
- **RFC 5988 `Link` headers** — deferred. The body envelope
  carries the same information; a future slice can add Link
  headers additively if a HAL/HATEOAS client surfaces.
- **Total-count return** — rejected (§2.4). FHIR Bundle agrees.
- **Per-domain cursors with no shared substrate** — rejected.
  This is exactly what the slice exists to prevent.

## 5. Implementation chunks

| Chunk | Scope |
|---|---|
| **A** (this commit) | ADR-009 + Kotlin `read.pagination` substrate + frontend `lib/pagination.ts` substrate |
| **B** | Apply to encounter-notes (smallest sort axis) + V26 index + ArchUnit Rule 15 |
| **C** | Apply to encounters + V27 index |
| **D** | Apply to allergies + V28 index |
| **E** | Apply to problems + V29 index — closes the original CodeRabbit finding |
| **F** | Frontend: rewire all 4 cards/lists to consume the new envelope; tests follow |
| **G** | Governance + commit + push + CodeRabbit + merge |

Each chunk is independently shippable and reviewable. The pattern
established in chunk B is mechanically applied to C/D/E.
