# ADR-002: Authentication is OIDC-only; local dev uses `mock-oauth2-server`; production IdP choice deferred

- **Status:** Proposed
- **Date:** 2026-04-20
- **Authors:** Gurinder Singh
- **Reviewers:** Gurinder Singh (repository owner)
- **Supersedes:** none
- **Related:** ADR-001 (persistence), ADR-003 (audit),
  `.cursor/rules/01-security-invariants.mdc`, `AGENTS.md` §§3.1, 4.1

---

## 1. Context

`AGENTS.md` §3.1 and Rule 01 make two commitments absolute:

- Authentication logic MUST be centralized. Ad-hoc auth is prohibited.
- Custom authentication is prohibited; centralized libraries are required.

Medcore must therefore integrate with an OpenID Connect identity provider
(IdP), validate JWT bearer tokens at the resource-server boundary, and
never implement passwords, sessions, or token issuance itself.

For Phase 3 we need:

- A concrete local dev path that lets the backend validate real OIDC tokens
  today, without standing up a full production IdP.
- A production IdP strategy that stays open long enough to evaluate real
  constraints (BAA availability, per-tenant SSO, cost at MAU scale) rather
  than locking in based on intuition.
- Application code that does not know which IdP is behind the JWKS URL —
  so switching providers is configuration, not rewriting.

A previous iteration of the Phase 3 plan proposed Keycloak for local dev.
That option was rejected by the repository owner as too operationally heavy
for a solo builder at this phase: realm UIs, client setup wizards, and
regular version upgrades are a project inside the project. A lighter,
still-OIDC-compliant option is `ghcr.io/navikt/mock-oauth2-server`: a single
container, OIDC discovery endpoint, JWKS endpoint, programmable token
issuance — and nothing else to maintain.

## 2. Decision

**We will use OpenID Connect exclusively for authentication, validate JWTs
using Spring Security's OAuth2 resource-server, run
`ghcr.io/navikt/mock-oauth2-server` as the local and Testcontainers IdP,
and defer the production IdP choice to a later ADR.**

Specifics:

- **Protocol:** OIDC; JWT access tokens (or equivalent JWT shape); RS256
  minimum. JWKS URL, issuer URI, audience, and required claims come from
  `MedcoreOidcProperties`.
- **Backend library:** `spring-boot-starter-oauth2-resource-server`. No
  custom JWT verification code.
- **Local dev IdP:** `ghcr.io/navikt/mock-oauth2-server`, pinned to a
  specific tag in `docker-compose.yml`. One issuer, multiple test users
  configured via JSON. Accessible on `http://localhost:8888` (or similar).
- **Testcontainers IdP:** same image, started per integration test class
  where token issuance is required.
- **User model:** one canonical internal `identity.user` row per
  `(issuer, subject)` pair. Just-in-time provisioned on first successful
  token validation.
- **Production IdP:** production IdP choice is deferred to a future ADR
  and will be evaluated against BAA availability, cost, operational load,
  enterprise SSO requirements, and developer ergonomics. No vendor is
  pre-committed here.
- **Guardrail:** `MedcoreOidcProperties` rejects an issuer containing
  `mock-oauth2-server` (or `localhost`) under any `prod` Spring profile;
  startup fails before serving traffic. This prevents accidental
  production deployment of the dev IdP.

## 3. Alternatives Considered

### Keycloak (locally hosted)
Full-featured, production-viable, free. Rejected for local dev at this
phase because its operational cost (realm setup, upgrades, memory
footprint, documentation of each flow) is disproportionate to the
Phase 3 proof-of-loop goal. Reconsidered when per-tenant SSO, federation,
or group-based roles become concrete requirements.

### Managed OIDC SaaS for local dev
Excellent developer experience but introduces network-dependent development
flows, vendor signup friction, and potentially non-trivial cost
commitments (especially where BAA tiers are involved). Rejected for
Phase 3 local dev. Remains on the table for the production-IdP evaluation.

### Build a minimal in-house IdP (Spring Authorization Server, Ory Hydra, or similar)
Rejected. Owning an IdP is exactly the side project the repository owner
ruled out for this phase. Strong for a staffed team; inappropriate for a
solo operator at Phase 3.

### Username/password auth of any shape
Prohibited by Rule 01. Not considered.

### Permit an "auth disabled" dev mode
Prohibited. A local feature flag that bypasses authentication is exactly
how production incidents start. All local dev traffic authenticates against
the mock IdP.

## 4. Consequences

### 4.1 Positive
- The application code is 100 % standard OIDC/JWT — zero dev-IdP-specific
  logic. Swapping to any OIDC-compliant production IdP is an env-var
  change.
- A single `docker-compose up` gives a developer a working authentication
  loop with no vendor signup.
- Testcontainers integration tests can mint tokens without mocking
  Spring Security internals — they hit a real JWKS endpoint.
- The production IdP decision is preserved for when real evidence exists,
  reducing lock-in risk.

### 4.2 Negative
- `mock-oauth2-server` is explicitly dev-only; we carry the guardrail
  burden of preventing its use in production.
- Between ADR acceptance and the production-IdP ADR, Medcore cannot ship
  to production. Acceptable because no non-local deployment exists yet.
- Some IdP-specific behaviors (refresh tokens, logout semantics, device
  flow, session revocation) are not exercised by the mock; those
  behaviors will be re-tested against the production IdP when chosen.

### 4.3 Risks & Mitigations
- **Accidental production use of `mock-oauth2-server`.** Mitigation:
  `MedcoreOidcProperties` init check; integration test asserts that a
  forbidden issuer causes startup failure under `prod` profile.
- **Drift between dev and production IdP behaviors.** Mitigation: the
  production-IdP ADR will require a smoke-test suite that runs against
  the real IdP before promotion.
- **Token shape assumptions baked into code.** Mitigation: the JWT
  authentication converter reads standard claims only (`iss`, `sub`,
  `email`, `preferred_username`, `aud`); anything non-standard is a
  configuration field, not a hardcoded string.

## 5. Compliance & Security Impact

- **HIPAA 45 CFR §164.308(a)(4)** (administrative safeguards — access
  management): centralized authentication is the required posture.
- **HIPAA technical safeguards** (§164.312(d) — authentication):
  satisfied at the IdP level once a production IdP is chosen.
- **SOC 2 CC6.1 / CC6.6** — authentication and credential lifecycle —
  satisfied by relying on a dedicated IdP.
- **Rule 01** — prohibition on custom authentication — satisfied.
- **Secrets handling:** no credentials in the repo. Issuer URI, audience,
  required claims are environment variables consumed via
  `MedcoreOidcProperties`.

## 6. Operational Impact

- **Local:** one additional container (`mock-oauth2-server`); memory
  footprint is negligible.
- **CI:** Testcontainers pulls the mock image once per cache; runs are
  fast.
- **Production:** deferred. The production-IdP ADR will define hosting,
  SLA, rotation, BAA, SSO integration, and cost expectations.
- **Developer onboarding:** covered by a follow-up runbook
  (`docs/runbooks/identity-local-dev.md`).

## 7. Rollout Plan

1. Accept this ADR.
2. Add `mock-oauth2-server` to `docker-compose.yml` with a pinned tag and
   a config file defining two test users.
3. Introduce `MedcoreOidcProperties` in `platform/config/`, with the
   `prod` profile guardrail.
4. Wire Spring Security resource-server JWT decoder using
   `MedcoreOidcProperties.issuerUri`. All `/api/**` requires auth.
5. Land `identity.user` table (ADR-001 migration step) and JIT
   provisioning on first successful token validation.
6. Implement `GET /api/v1/me` as the first authenticated endpoint.
7. Integration tests using Testcontainers `mock-oauth2-server` mint tokens
   for the two test users and assert happy-path + 401 behavior.
8. Future ADR: pick production IdP. Rotate issuer configuration in the
   environment. No code change expected.

**Rollback plan:**
- Removing the mock IdP from local dev is a `docker-compose` edit;
  reversible immediately.
- If this ADR is superseded to choose a different dev IdP (for example,
  Keycloak becomes a hard requirement), the backend code does not
  change — only configuration and `docker-compose.yml`.
- Permanent exit from OIDC as a protocol is inconceivable without
  replacing this ADR wholesale.

## 8. Acceptance Criteria

- [ ] `docker-compose.yml` includes `ghcr.io/navikt/mock-oauth2-server`
      at a pinned tag with a committed config.
- [ ] `MedcoreOidcProperties` binds cleanly; `prod` profile fails startup
      if issuer references `mock-oauth2-server` or `localhost`.
- [ ] Spring Security resource-server validates tokens against the mock
      issuer.
- [ ] `identity.user` JIT provisioning works idempotently on first token.
- [ ] Integration tests: happy path to `/api/v1/me`; 401 without token;
      401 with token signed by the wrong issuer.
- [ ] `docs/runbooks/identity-local-dev.md` authored or has a tracking
      issue with an owner.
- [ ] Production IdP ADR opened as a follow-up.

## 9. References

- `AGENTS.md` §§3.1, 4.1
- `.cursor/rules/01-security-invariants.mdc`
- OpenID Connect Core 1.0
- RFC 7519 (JSON Web Token)
- Spring Security — OAuth2 Resource Server
- Nav / `mock-oauth2-server` — project README
