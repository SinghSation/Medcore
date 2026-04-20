# ADR-000: <Short, specific decision title>

- **Status:** Proposed | Accepted | Superseded by ADR-NNN | Deprecated
- **Date:** YYYY-MM-DD
- **Authors:** <name(s)>
- **Reviewers:** <name(s)>
- **Supersedes:** <ADR-NNN, or "none">
- **Related:** <ADR-NNN, docs, issues>

> **Instructions (delete before submitting):**
> Copy this file to `docs/adr/NNN-short-kebab-title.md` using the next
> sequential number (zero-padded, three digits). Do not re-use a number.
> ADRs are immutable once accepted: to change a decision, write a new ADR
> that supersedes this one.

---

## 1. Context

State the forces that led to this decision. What is the problem? What
constraints (regulatory, technical, cost, people) apply? What is currently
true in the repository that makes this decision necessary?

Keep this section factual. No proposed solutions here.

## 2. Decision

State the decision in one or two sentences using active voice.

> **We will <verb> <thing> because <reason>.**

Follow with the specific changes this decision entails: files, boundaries,
processes, or contracts that will change.

## 3. Alternatives Considered

List at least two genuine alternatives. For each, state:

- What it is.
- Why it was rejected.
- What would make us reconsider it in the future.

A decision without credible alternatives is not an architectural decision —
it is a preference.

## 4. Consequences

### 4.1 Positive
- What improves as a result of this decision.

### 4.2 Negative
- What becomes harder, slower, or more expensive.

### 4.3 Risks & Mitigations
- Named risk → concrete mitigation.

## 5. Compliance & Security Impact

Describe the effect on:

- PHI handling and data classification.
- Authentication, authorization, and audit surfaces.
- Applicable regulations (HIPAA, HITECH, SOC 2, FHIR, GDPR).
- Data residency and retention.

If this ADR has no compliance or security impact, state that explicitly and
justify it.

## 6. Operational Impact

Describe the effect on:

- Deployment, rollout, and rollback procedures.
- On-call load, runbooks, alerting, dashboards.
- Cost (one-time and recurring).
- Developer experience and build times.

## 7. Rollout Plan

1. Step one — what changes first.
2. Step two — what changes after verification.
3. ...
4. Verification criteria — how we know the rollout succeeded.
5. Rollback plan — precise, rehearsed steps to undo.

## 8. Acceptance Criteria

A checklist that must be satisfied before this ADR is marked Accepted:

- [ ] Charter and `AGENTS.md` unchanged, OR amendment ADR attached.
- [ ] Required schemas, rules, and tests updated in the same PR.
- [ ] Threat model updated (if applicable).
- [ ] Runbook updated (if applicable).
- [ ] Human owner approval recorded.

## 9. References

- Links to specs, RFCs, regulations, prior ADRs, tickets, or internal docs.
