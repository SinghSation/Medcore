# ADR-008: Production IdP — WorkOS as identity broker

- **Status:** Accepted
- **Date:** 2026-04-23
- **Authors:** Gurinder Singh
- **Reviewers:** Gurinder Singh (repository owner)
- **Supersedes:** Carry-forward from Phase 3A.3 ADR-002 "Production
  IdP decision" — locked with this ADR.
- **Related:** ADR-002 (OIDC identity for local dev), ADR-005
  (product framework), ADR-007 (WriteGate mutation architecture)

---

## 1. Context

Phase 3A.3 established Medcore's local-dev identity substrate
(`mock-oauth2-server` as the OIDC issuer, JIT provisioning into
`identity.user`). ADR-002 explicitly deferred the production IdP
choice as a carry-forward to Phase 3K.

Phase 4A (patient registry, the first clinical module) cannot
land correctly without a stable identity contract — clinical data
will reference `identity.user.id` in audit rows, encounter
attribution, order authoring, etc. If the identity surface shifts
after 4A ships, the migration cost compounds across every
clinical domain.

Phase 3K.1 locks the contract + vendor decision so 4A design can
proceed. Phase 3K.2 (deferred) handles the concrete vendor
integration alongside the Phase 3I.4 AWS substrate.

### 1.1 What kind of identity surface does Medcore need?

Medcore is an **enterprise healthcare platform**, not a generic
SaaS. The identity surface must accommodate:

- **Workforce identity** — clinicians, admins, staff at the
  customer practice. Phase 3J's `MembershipRole` + tenant
  scoping is the Medcore-side model.
- **Enterprise SSO federation** — a 50-clinic chain using Okta
  / Azure AD / Google Workspace expects to SSO from their IdP
  into Medcore. This is the differentiating feature of
  healthcare B2B — every non-trivial pilot will want it.
- **Patient identity (Phase 6B, separate)** — different IdP
  configuration per ADR-002; explicitly out of scope for this
  ADR.
- **Service accounts (Phase 4F+)** — lab results ingestion,
  webhook receivers. Planned via a separate bootstrap flow;
  this ADR does not cover service-account identity.

### 1.2 Candidate vendors

Evaluated as part of this decision:

- **AWS Cognito** — AWS-native, cheapest at pilot scale, but
  non-standard claims + weaker SSO federation story.
- **Auth0** — mature, clean OIDC, strong SSO. Enterprise
  pricing; more features than this platform needs.
- **WorkOS** — purpose-built for B2B SaaS with enterprise SSO
  federation. Cleaner OIDC than Cognito, simpler pricing than
  Auth0.
- **Okta (Auth0's parent)** — enterprise-scale; overkill at
  pilot; slow to set up.
- **Clerk** — consumer-focused; patient-side candidate for
  Phase 6B, not workforce.

## 2. Decision

### 2.1 Vendor — WorkOS

**WorkOS is the production workforce-identity broker.** The
decision rationale:

- **Purpose-built for B2B SaaS with SSO federation.** When a
  healthcare customer brings their own IdP (Okta / Azure AD /
  Google Workspace), WorkOS handles the federation via
  configuration; competitors require engineering-heavy
  adapters.
- **Clean OIDC compliance.** Tokens are standards-compliant;
  Medcore's `ClaimsNormalizer` becomes a strict validator
  rather than a vendor-specific remapper.
- **Pilot pricing.** AuthKit + one SSO connection: ~$125/mo
  at pilot scale. Free below the SSO threshold. Cognito is
  cheaper but loses on SSO; Auth0 is more expensive with
  minor additional value for our scope.
- **Separation from AWS control plane.** WorkOS runs as a
  SaaS alongside AWS infra rather than entangling with IAM —
  IdP outages and AWS outages are independent failure modes.

### 2.2 WorkOS's role — broker, NOT system of record

**Normative.** This is the architectural tightening that
frames every subsequent design decision:

> **WorkOS is an identity broker / orchestration layer.**
> **Medcore remains the system of record for identity
> lifecycle (status, tenancy, audit linkage).**

WorkOS is responsible for:

- Routing authentication requests (username+password,
  passkey, SSO federation to enterprise IdPs)
- Enforcing IdP-side MFA policies
- Issuing OIDC-compliant ID tokens
- Propagating IdP-side deactivation via token-expiry
  (disabled users stop getting new tokens)

WorkOS is NOT responsible for:

- Storing Medcore's `identity.user.status` (DISABLED /
  DELETED lifecycle states)
- Resolving tenant memberships
- Enforcing Medcore-side business rules (last-OWNER, role
  escalation guards)
- Owning audit rows referencing `actor_id`

A broker swap (WorkOS → any other OIDC-compliant vendor)
does NOT require data migration — the `identity.user` table
is tied to `(issuer, subject)` pairs, which the new broker
will emit differently, but the Medcore-internal `userId`
(UUID) never changes.

### 2.3 Identity contract — normative

Every inbound authenticated request carries a
[`MedcorePrincipal`](../../apps/api/src/main/kotlin/com/medcore/platform/security/MedcorePrincipal.kt)
with the following shape. This contract is STABLE across
vendor choices; a broker swap changes the token-to-principal
mapping, not the principal shape.

```
MedcorePrincipal
  userId: UUID                 — Medcore-internal identifier; stable across broker swaps
  issuerSubject: (iss, sub)    — external link to broker; resolves to userId via identity.user
  email: String?               — present only when emailVerified=true
  emailVerified: Boolean       — always TRUE for authenticated principals
  displayName: String?         — single string, never multi-part
  preferredUsername: String?   — display-only, never an identifier
  status: PrincipalStatus      — ACTIVE | DISABLED | DELETED (Medcore-owned)
  issuedAt / expiresAt: Instant
```

### 2.4 Claims normalization — strict validator, no remapping

[`ClaimsNormalizer`](../../apps/api/src/main/kotlin/com/medcore/platform/security/ClaimsNormalizer.kt)
runs at the `MedcoreJwtAuthenticationConverter` boundary BEFORE
a principal is built. It enforces:

- `sub` present + non-blank
- `iss` present + non-blank
- `email_verified == true` when `email` is present

It does NOT remap claim names — WorkOS emits clean OIDC.
The class exists as vendor-swap insurance: if a future
slice ever swaps WorkOS for Cognito, a second implementation
lands in this seam with the `cognito:groups → groups` (etc.)
remapping.

Spring Security's `JwtDecoder` continues to handle
signature + issuer-match + audience + time windows —
duplicating those checks in the normalizer would be a trust
boundary violation.

### 2.5 Tenant mapping — lookup, not token claim

Tenant is resolved per-request from `principal.userId` →
`tenancy.tenant_membership` lookup (Phase 3J `AuthorityResolver`).
Tenant is NEVER embedded as a token claim.

Rationale:
- Multi-tenant users are common (same user is OWNER of tenant
  A, MEMBER of tenant B). Embedding tenant in the token means
  re-issuing tokens on tenant switch.
- Token becomes stateful (tied to a tenant context), breaking
  the stateless-resource-server posture.
- The existing 3J+3J.N RLS + WriteGate stack already
  implements the lookup cleanly; V13's `caller_is_tenant_admin`
  helper keeps the lookup path performant.

### 2.6 Medcore `PrincipalStatus` is authoritative — NORMATIVE

**Locked invariant:**

> A cryptographically-valid token that maps to an
> `identity.user` row with `status != ACTIVE` MUST be
> REJECTED with 401 at the Medcore authentication
> boundary, even when the broker's token is otherwise
> valid.

IdP-side deactivation propagates via token expiry (the
broker stops issuing NEW tokens for the disabled user), but
existing un-expired tokens must be revocable at the Medcore
layer — the broker is an orchestration layer, not the
system of record.

**Implementation (Phase 3K.1):**

[`IdentityProvisioningService.resolve`](../../apps/api/src/main/kotlin/com/medcore/identity/IdentityProvisioningService.kt)
checks `entity.status` after lookup. If DISABLED or DELETED,
throws
[`PrincipalStatusDeniedException`](../../apps/api/src/main/kotlin/com/medcore/platform/security/PrincipalStatusDeniedException.kt)
with a closed-enum reason code. The exception extends Spring's
`DisabledException` (which extends `AuthenticationException`)
so Spring Security's exception-translation pipeline routes it
to
[`AuditingAuthenticationEntryPoint`](../../apps/api/src/main/kotlin/com/medcore/platform/security/AuditingAuthenticationEntryPoint.kt)
as a 401.

The entry point detects the specific exception type via a
cause-chain walk and emits `IDENTITY_USER_LOGIN_FAILURE`
with:
- `actor_id` = the rejected user's Medcore UUID (forensics
  value for compliance — a terminated employee's failed
  access attempt is exactly what audit wants to record)
- `reason` = closed-enum slug (`principal_disabled` or
  `principal_deleted`; never a free-form message)

No `IDENTITY_USER_LOGIN_SUCCESS` is emitted for a rejected
principal — "login success" means "Medcore accepted the
session," which we explicitly did not.

### 2.7 User lifecycle — JIT provisioning + deactivation +
deletion

**JIT provisioning (existing, confirmed).** First
authenticated request for a new `(issuer, subject)` creates
the `identity.user` row via
`IdentityProvisioningService.resolve`. Idempotent — a race
on a new `(issuer, subject)` is caught by the unique
constraint; the retry finds the existing row.

**Deactivation (new path in 3K.1).** Medcore's
`identity.user.status` transitions to `DISABLED` via either:
(a) admin operator action (a future slice may wire this
through a WriteGate-gated handler), (b) bulk import from HR
off-boarding, (c) compliance-triggered termination.
Subsequent requests fire `PrincipalStatusDeniedException`.

**Deletion.** Medcore retains the `identity.user` row with
`status=DELETED` for audit-reference integrity
(`audit.audit_event.actor_id` references are preserved).
Actual cascade-delete of downstream rows (tenant_membership,
audit rows, etc.) is a Phase 7+ GDPR slice — out of scope
for 3K.1.

**Re-creation after deletion.** A new request for the same
`(issuer, subject)` post-deletion is treated as a NEW user
(new Medcore `userId`). Tombstone semantics: deletion is
permanent at the Medcore identity level. The prior deleted
row is preserved but no longer resolves to a login session.

### 2.8 MFA — delegated to the IdP, trusted via `amr`

**Decision:** Medcore TRUSTS the broker's MFA assertion via
the `amr` (Authentication Methods References, RFC 8176)
claim. No Medcore-side MFA enforcement.

**For workforce identity:** MFA MUST be enforced at the
broker level. ADR-008 stipulates this as a broker
configuration requirement — not a Medcore code check. A
future slice (Phase 3K.2 or similar) lands a verification
test asserting the dev-environment broker configuration has
MFA-required policy.

**Session expiry:** tokens MUST have
`exp <= issued_at + 1 hour` for workforce. Medcore rejects
tokens beyond this at the JWT validation layer (configured
in `SecurityConfig`). This pre-existed Phase 3K.1 but is
documented here explicitly.

Rationale for delegation:
- Medcore's role is EHR, not auth infrastructure.
- Competing MFA enforcement (broker-side + Medcore-side)
  confuses the UX and audit trail.
- MFA-audit lineage goes through the broker's SOC 2 Type II
  report rather than Medcore having to establish its own.

## 3. Alternatives Considered

### 3.1 AWS Cognito

**What:** Use Cognito as the production workforce IdP.
**Why rejected:**
- `cognito:groups` + `token_use` quirks force ongoing
  normalization maintenance, raising the cost of every
  future claim-handling change.
- SSO federation in Cognito requires per-IdP Lambda triggers
  and is slow to set up; WorkOS does it in a config dialog.
- "AWS alignment" argument is weaker than it looks — Cognito
  runs entirely in the AWS control plane, meaning an IAM
  misconfiguration can take down both infra and auth. WorkOS
  separates the failure modes.

### 3.2 Auth0

**What:** Use Auth0 as the production workforce IdP.
**Why rejected:**
- Pricing is enterprise-scale; pilot cost ~5x WorkOS for
  no additional capability Medcore uses.
- Owned by Okta — merging these two at some point is a real
  risk; WorkOS is an independent vendor with a clearer road
  map for B2B SaaS.
- Auth0 bundles features (actions, custom DBs, social
  connections) Medcore explicitly does NOT want; the larger
  surface = larger attack surface.

### 3.3 Embed tenant ID in a custom token claim

**What:** Instead of tenant lookup per request, embed a
`tenant` claim in the token.
**Why rejected:**
- Multi-tenant users require re-issuing tokens on tenant
  switch — broken UX.
- Token becomes stateful (tied to a tenant context),
  breaking the stateless-resource-server posture.
- Medcore's V13 `caller_is_tenant_admin` SECURITY DEFINER
  function + existing RLS machinery already implement the
  lookup efficiently.

### 3.4 Medcore enforces MFA directly

**What:** Medcore-side MFA enforcement as a separate layer
above the broker's.
**Why rejected:**
- Duplicates what the broker already does, at a cost to
  both Medcore engineering time and MFA UX.
- Competing MFA policies between broker + Medcore produce
  confusing error states ("logged in via broker MFA but
  Medcore wants another factor").
- Medcore's role is EHR. Auth infrastructure is the broker's
  job.

## 4. Consequences

### 4.1 Positive

- **4A unblocked.** The identity contract (§2.3) is stable
  enough to begin patient-entity domain design.
- **SSO federation path clear.** When a healthcare customer
  brings their own IdP, WorkOS handles it; Medcore stays
  unchanged.
- **Vendor-swap insurance.** `ClaimsNormalizer` is the
  single file that changes if we ever swap brokers.
  `identity.user` schema does not migrate.
- **Medcore-authoritative status invariant.** Terminated
  employees cannot access the system via old un-expired
  tokens — a real compliance requirement (HIPAA workforce
  access termination procedures).

### 4.2 Negative

- **Cost grows with SSO connections.** Each enterprise
  customer requiring their own SSO connection adds ~$125/mo.
  Acceptable for pilot; revisit pricing tier when customer
  count > 5.
- **Vendor dependency.** WorkOS outage = Medcore login
  outage. Mitigated by: (a) SOC 2 Type II on the vendor
  side; (b) WorkOS's redundant infrastructure; (c) the
  vendor-swap insurance in §2.4.
- **Developer workflow change.** New engineers must
  understand "identity.user is source of truth, not the
  token" — handled via the new
  `docs/runbooks/identity-idp.md`.

### 4.3 Compliance

- **HIPAA §164.308(a)(3)(B)** (workforce termination
  procedures): Medcore-authoritative `PrincipalStatus` is
  the technical control that enforces revocation of
  terminated employees' access even when their IdP token
  has not yet expired.
- **HIPAA §164.308(a)(4)** (workforce access authorization):
  broker-enforced MFA + Medcore-side status check + V12 RLS
  + WriteGate layered controls.
- **SOC 2 CC6.1** (logical-access changes): MFA lineage
  routes through WorkOS's SOC 2 Type II report; Medcore
  references it rather than auditing itself.
- **SOC 2 CC6.3** (workforce removal): Medcore-authoritative
  status check closes the "old token used after termination"
  window documented in every SOC 2 pentest finding database.

## 5. Rollout Plan

- **Phase 3K.1 (this ADR).** Lock vendor + contract + status
  authority invariant. Ship `ClaimsNormalizer` +
  `PrincipalStatusDeniedException` + auth-entry-point
  integration + DISABLED-rejection test. Runbook for
  operators.
- **Phase 3K.2 (deferred).** Concrete WorkOS integration in
  `dev` environment (Terraform + application config +
  mock-OIDC-server retained for local dev). Lands alongside
  Phase 3I.4 AWS substrate.
- **Phase 4A.** First clinical module references
  `identity.user.id` confidently because the identity
  contract is locked.
- **Phase 6B.** Patient-facing IdP — separate ADR,
  independent decision per ADR-002 §2.

## 6. Acceptance

Closed by:
- [x] Vendor choice named: WorkOS
- [x] Identity contract locked (§2.3) as normative
- [x] WorkOS role as broker vs Medcore as source of record
      documented (§2.2)
- [x] `ClaimsNormalizer` shipped as strict OIDC validator
      (§2.4)
- [x] Tenant lookup model confirmed (§2.5)
- [x] Medcore `PrincipalStatus` authoritative invariant
      (§2.6) — implemented + tested
- [x] MFA delegation posture (§2.8)
- [x] `docs/runbooks/identity-idp.md` landed

## 7. References

- ADR-002 — OIDC identity for local dev (carry-forward
  origin)
- ADR-005 — Product direction framework
- ADR-007 — WriteGate mutation architecture
- `docs/runbooks/identity-idp.md` — Operator runbook
- RFC 8176 — Authentication Method Reference Values (`amr`)
- RFC 6750 — OAuth 2.0 Bearer Token Usage (error_description
  constraints that shape `ClaimsNormalizer`'s exception
  messages)
- HIPAA §§164.308(a)(3)(B), 164.308(a)(4)
- SOC 2 CC6.1, CC6.3
