---
name: phi-exposure-review
description: Use before merging any change that could touch PHI. Structured review that surfaces every potential PHI exposure in a diff — logs, errors, analytics, prompts, storage, exports.
---

# Skill: phi-exposure-review

PHI leakage is the most consequential bug class in this codebase. This
skill produces a structured review that MUST be attached to any PR whose
diff could touch PHI.

## When to use

Run this review when the change touches any of:

- A schema or type in `packages/schemas/` carrying PHI.
- An API endpoint, event, or queue whose payload may include PHI.
- Logging, metrics, tracing, audit, or analytics code paths.
- Error handling or reporting (Sentry, Crashlytics, equivalents).
- AI prompt construction, retrieval, or logging.
- Client-side state, cache, or persistence.
- Data export, reporting, or support-tool flows.
- Infrastructure touching storage, backup, or cross-region replication.

## Procedure

Produce a markdown report with the sections below. Attach it to the PR.
Do NOT approximate; cite file paths and line ranges.

### 1. Scope of the diff

- Files changed.
- Summary of the change in one paragraph.
- Modules affected.

### 2. Data inventory

For every field moving in or out in this diff, list:

| Field | Source | Destination | Classification | Tagged in schema? |
| ----- | ------ | ----------- | -------------- | ----------------- |

Classification is one of: `PHI`, `PII`, `Confidential`, `Internal`, `Public`.

If a field is PHI and is not tagged in the schema layer, this is a **block**.

### 3. Exposure checklist

Answer **Yes / No / N/A**, with a reference (file:line) for every Yes:

- [ ] Does this change write a PHI field to a structured log?
- [ ] Does it write PHI to an error message or exception?
- [ ] Does it include PHI in a metric label, span attribute, or trace event?
- [ ] Does it send PHI to an analytics, session-replay, or telemetry sink?
- [ ] Does it embed PHI in a URL, query string, or path parameter?
- [ ] Does it persist PHI to `localStorage`, `sessionStorage`, `IndexedDB`,
      a cookie, or a non-encrypted cache?
- [ ] Does it include PHI in an email, webhook, or outbound integration?
- [ ] Does it include PHI in a build artifact, snapshot, or generated file?
- [ ] Does it pass PHI into an AI prompt, embedding call, or model log?
- [ ] Does it expand the set of roles / tenants that can read this data?
- [ ] Does it export PHI to a file, CSV, PDF, or download?
- [ ] Does it appear in a test fixture, seed, or README example?

Any unresolved **Yes** requires a fix or an explicit, time-bounded waiver
recorded in an ADR.

### 4. Redaction verification

- Identify the logger / tracer / analytics client used.
- Confirm it applies schema-driven redaction (not string replacement).
- If the change introduces a new sink, confirm it is registered with the
  redaction layer.

### 5. Access-control verification

- Which authz check protects this data?
- Is tenancy enforced at the data-access boundary (not only the UI)?
- Are there new code paths where an authz check is missing?

### 6. AI-specific checks (only if AI is involved)

- Is the AI provider covered by a signed BAA?
- Is the prompt constructed from PHI directly, or from de-identified /
  tokenized inputs?
- Is the prompt and response logged? If so, with what redaction?
- Is there a documented fallback when the model is unavailable or unsafe?
- Does the change alter any existing audit event for AI interactions?

### 7. Tests

For every Yes in §3 that is mitigated in the diff, cite a test that would
catch a regression. If no such test exists, add one.

### 8. Risk summary

One paragraph rating the residual exposure risk:

- **None** — no PHI-adjacent surface touched.
- **Low** — PHI-adjacent, but all sinks verified and tested.
- **Medium** — mitigations present, but test coverage thin.
- **High** — unresolved exposure OR missing mitigations OR missing tests.

A **Medium** rating requires reviewer acknowledgement. A **High** rating
blocks the merge.

### 9. Sign-off

- Author confirms the review was performed on the *final* diff, not an
  earlier draft.
- Reviewer confirms the inventory matches the diff.

## Assistant behavior

When invoked, the assistant MUST:

1. Re-read the diff from scratch. Do not rely on summaries from earlier
   turns.
2. Cite file paths and line ranges for every answer.
3. Flag any **High** item immediately and stop, requesting human decision.
4. Never mark the review complete while the checklist contains an
   unresolved **Yes**.
