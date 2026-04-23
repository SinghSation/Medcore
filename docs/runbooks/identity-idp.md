# Identity / IdP Runbook — Phase 3K.1

This runbook describes Medcore's workforce-identity
architecture, the role of the production IdP (WorkOS), and
operator-facing procedures for rotation, termination, and
vendor-swap contingency. Governing ADR: `docs/adr/008-production-idp-decision.md`.

---

## 1. Mental model (read this first)

Two sentences, memorize them:

1. **WorkOS is a broker.** It routes auth requests, federates
   SSO to customer IdPs, and issues OIDC tokens.
2. **Medcore owns identity lifecycle.** The `identity.user`
   table is the source of truth for user status, tenancy,
   and audit linkage.

Every operational decision flows from this separation:

- Disabling a user? Update `identity.user.status`. WorkOS
  can stay unchanged — the next request with the old token
  is rejected by Medcore.
- Rotating broker credentials? Update WorkOS. `identity.user`
  stays unchanged because `(issuer, subject)` pairs are
  stable across credential rotations.
- Swapping brokers entirely? The `ClaimsNormalizer` changes
  and the new broker emits new `(issuer, subject)` pairs.
  `identity.user.id` values do NOT change.

---

## 2. Environments

| Environment | Broker | Notes |
|---|---|---|
| `local` | `no.nav.security:mock-oauth2-server` | In-process JVM; zero-config. `TestcontainersConfiguration` spins it up per test class. |
| `dev` | WorkOS (AuthKit) | Configured in Phase 3K.2 (deferred to 3I.4 AWS landing). |
| `staging` | WorkOS (AuthKit) | Separate WorkOS workspace from `dev` for isolation. |
| `prod` | WorkOS (AuthKit) + per-customer SSO connections | Each enterprise customer with SSO: one WorkOS SSO connection. |

Local dev continues to use mock-oauth2-server **indefinitely** —
not a deprecated path. Running end-to-end tests against the
production broker in CI is expensive + fragile; the mock
server is isomorphic to the broker's OIDC surface for
development purposes.

---

## 3. Daily operations

### 3.1 Disabling a workforce user

**Scenario:** an employee leaves the practice. Their Medcore
access must stop immediately.

**Remediation:**

1. **Update Medcore first.**
   ```sql
   UPDATE identity."user"
      SET status = 'DISABLED',
          updated_at = NOW()
    WHERE id = '<user_uuid>';
   ```
   After this, any subsequent request with their existing
   token fails with 401 + an `IDENTITY_USER_LOGIN_FAILURE`
   audit row carrying `actor_id` + `reason=principal_disabled`.

2. **Update WorkOS.** Disable the user in the broker's
   admin console. This prevents token re-issuance after the
   current token expires (workforce tokens expire in ≤1
   hour). Between steps 1 and 2, Medcore is already
   enforcing — step 2 closes the re-issuance window.

**Why Medcore first?** If you disable at WorkOS only, the
user can continue to access Medcore for up to the token
TTL (~1 hour). If you disable at Medcore first, access
stops immediately — then WorkOS disable closes the
re-login path.

### 3.2 Deleting a workforce user (GDPR-adjacent)

**Scenario:** GDPR data-subject deletion request OR
end-of-retention purge.

**3K.1 scope:** set `identity.user.status = 'DELETED'`. The
row is preserved; `actor_id` references in `audit.audit_event`
remain resolvable (audit integrity).

**Phase 7+ scope (not 3K.1):** cascade-delete downstream
rows (tenant_membership, patient-side data, etc.) per a
dedicated GDPR slice with its own ADR.

### 3.3 Rotating WorkOS API credentials

**Cadence:** quarterly; immediately after any suspected
compromise.

**Procedure:**

1. Generate new API key in WorkOS console.
2. Update the secret in AWS Secrets Manager (Phase 3I.5
   substrate) under the relevant environment's secret name.
3. Rolling-restart the Medcore application to pick up the
   new secret.
4. Revoke the old API key in WorkOS console.
5. Record rotation in `docs/evidence/secrets-rotation/`.

### 3.4 Onboarding an enterprise customer with their own IdP

**Scenario:** 50-clinic chain using Okta wants SSO from
their Okta into Medcore.

**Procedure (high-level; full per-vendor steps in WorkOS
docs):**

1. Create a new SSO connection in WorkOS console under the
   customer's organization.
2. WorkOS generates an SP metadata URL + entity ID.
3. Customer's IT provisions the SP in their Okta.
4. Customer returns IdP metadata (URL or XML).
5. Input customer's IdP metadata into WorkOS connection.
6. Test SSO login flow with a designated customer user.
7. Once verified, enable the connection for the customer's
   WorkOS organization.

From Medcore's perspective, **no code changes are required**.
The SSO-federated user's JWT arrives at Medcore with the
customer's `iss` value. `(issuer, subject)` is unique per
customer's user; JIT provisioning creates the Medcore
`identity.user` row on first login. Tenant assignment
happens via standard Phase 3J membership invite.

---

## 4. Incident response

### 4.1 WorkOS outage

**Symptom:** all new authentication attempts fail; existing
sessions continue until their token expires (≤1 hour).

**Remediation:**
- Monitor WorkOS status page.
- No Medcore-side action required for users with
  un-expired tokens.
- If outage > 1 hour: merge freeze; announce to customers
  that logins are unavailable.
- Post-incident: review WorkOS's incident report; decide
  whether to adjust Medcore's token TTL as a mitigation
  (longer TTL = more tolerance for broker outages, but
  longer window for compromised tokens).

### 4.2 Broker-side credential compromise

**Symptom:** security alert from WorkOS that API keys leaked.

**Remediation:**
1. Rotate WorkOS API credentials IMMEDIATELY (§3.3).
2. Revoke all active WorkOS sessions via admin console.
3. Review `IDENTITY_USER_LOGIN_SUCCESS` audit rows for the
   compromise window; flag any anomalies (unusual IPs,
   unusual access patterns).
4. If clinical data access is suspected: invoke the incident
   response runbook (`docs/runbooks/incident-response.md`
   — future, Phase 7).

### 4.3 Suspected compromised workforce token

**Symptom:** unexplained `IDENTITY_USER_LOGIN_SUCCESS`
events for a user from unusual location.

**Remediation:**
1. Disable the user in Medcore FIRST (§3.1 step 1).
2. Disable in WorkOS.
3. Review audit trail for the compromise window.
4. Force the user to re-authenticate after reinstating
   (new token required).

---

## 5. Vendor-swap contingency

**Trigger scenarios:**
- WorkOS acquired by a vendor we cannot accept (competitor,
  high-risk jurisdiction, etc.).
- WorkOS-side SOC 2 audit findings that don't resolve.
- Pricing changes that exceed budget.

**Playbook:**

1. New vendor selected via superseding ADR.
2. Implement a new `ClaimsNormalizer` variant handling the
   new vendor's quirks (or the default pass-through if the
   new vendor is clean OIDC).
3. Phase-gate migration: both brokers active in parallel
   during transition. `(issuer, subject)` pairs uniquely
   identify which broker issued a given token; JIT
   provisioning creates new `identity.user` rows for the
   new-broker `(issuer, subject)` pairs.
4. Users re-authenticate through the new broker; their
   Medcore `userId` is NEW (new `(issuer, subject)`). Their
   tenant memberships transfer via a one-time migration
   script that looks up old-userId → new-userId via
   `preferredUsername` or `email` and updates
   `tenancy.tenant_membership.user_id` references.
5. Cut over DNS / WorkOS IdP-initiated login path to point
   at the new broker.
6. Decommission WorkOS after a 30-day overlap.

**What does NOT change during a swap:**
- `MedcorePrincipal` shape
- `ClaimsNormalizer`'s public interface
- Any Medcore business logic (WriteGate, RLS, audit)
- Audit rows (already carry internal `actor_id`, not broker
  subject strings)

---

## 6. Token + claim inspection

For debugging a failed authentication:

```bash
# Decode a JWT (never log tokens in shared channels)
echo "<token>" | cut -d. -f2 | base64 -d | jq .
```

Fields Medcore reads:
- `iss` — must match the configured issuer URI
- `sub` — the external subject identifier
- `email` — optional; if present, `email_verified` must be true
- `email_verified` — must be `true` when email is present
- `name` / `preferred_username` — display-only
- `exp` / `iat` — time windows, Spring Security validates
- `aud` — optional audience check, Spring Security validates
- `amr` — authentication method (for MFA verification;
  reserved for Phase 3K.2 assertions)

Fields Medcore IGNORES:
- Custom tenant claims (we use the lookup model per
  ADR-008 §2.5)
- Any vendor-specific claim not enumerated above

---

## 7. Common failures

| Symptom | Likely cause | Remediation |
|---|---|---|
| 401 on every request, clean token | Medcore `identity.user.status != 'ACTIVE'` | Check audit for `principal_disabled` / `principal_deleted`; restore status if unintended |
| 401 with `reason=invalid_bearer_token` | Malformed or expired token | Have user re-authenticate |
| 401 after `email_verified` policy change | IdP emitting `email_verified=false` | Configure broker to verify email before token issuance |
| `identity.user` row for user who left the org | Status not updated on termination | Update status per §3.1 |
| Same user has two `identity.user` rows | `(issuer, subject)` changed on broker swap or config change | Investigate; potentially merge via a one-off migration after confirming they're the same human |

---

## 8. Key files (for engineering reference)

| Concern | File |
|---|---|
| Principal shape | `apps/api/src/main/kotlin/com/medcore/platform/security/MedcorePrincipal.kt` |
| JWT → principal mapping | `apps/api/src/main/kotlin/com/medcore/platform/security/MedcoreJwtAuthenticationConverter.kt` |
| Strict OIDC validation | `apps/api/src/main/kotlin/com/medcore/platform/security/ClaimsNormalizer.kt` |
| Medcore-status-authoritative rejection | `apps/api/src/main/kotlin/com/medcore/platform/security/PrincipalStatusDeniedException.kt` |
| JIT provisioning + status check | `apps/api/src/main/kotlin/com/medcore/identity/IdentityProvisioningService.kt` |
| Auth failure audit emission | `apps/api/src/main/kotlin/com/medcore/platform/security/AuditingAuthenticationEntryPoint.kt` |
| Security filter chain + bean wiring | `apps/api/src/main/kotlin/com/medcore/platform/security/SecurityConfig.kt` |
| OIDC configuration properties | `apps/api/src/main/kotlin/com/medcore/platform/config/MedcoreOidcProperties.kt` |

---

*Last reviewed: 2026-04-23 (Phase 3K.1 — ADR-008 + ClaimsNormalizer + Medcore-status-authoritative invariant).*
