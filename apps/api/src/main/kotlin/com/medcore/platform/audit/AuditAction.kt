package com.medcore.platform.audit

/**
 * Closed set of audit actions emitted by Medcore.
 *
 * The wire value ([code]) is what is persisted into `audit.audit_event.action`
 * and is part of the stable audit contract — it MUST NOT change for an
 * existing action once emitted in any environment.
 *
 * **Registry discipline** (mirrors the [com.medcore.platform.api.ErrorCodes]
 * rule from ADR-005 §2.3 for wire-facing stable identifiers). Adding a
 * new action requires, in the same slice:
 *   1. A new enum entry here with a stable dotted `code`.
 *   2. At least one test that asserts emission of the new action in
 *      the scenario that triggers it.
 *   3. A review-pack callout that the audit-action set is expanding.
 *   4. A runbook or DoD-row mention of when / why the action fires.
 *
 * Renaming or removing a code is a breaking audit-contract change —
 * handle via superseding ADR, never in-place.
 */
enum class AuditAction(val code: String) {
    // --- Phase 3C: identity + tenancy (ADR-003 §7) ---
    IDENTITY_USER_PROVISIONED("identity.user.provisioned"),
    IDENTITY_USER_LOGIN_SUCCESS("identity.user.login.success"),
    IDENTITY_USER_LOGIN_FAILURE("identity.user.login.failure"),
    TENANCY_CONTEXT_SET("tenancy.context.set"),
    TENANCY_MEMBERSHIP_LIST("tenancy.membership.list"),
    TENANCY_MEMBERSHIP_DENIED("tenancy.membership.denied"),

    // --- Phase 3F.4: audit chain integrity verification ---
    /**
     * Emitted exactly once per scheduled verification cycle when the
     * chain walk reports one or more breaks. Distinguishes chain
     * integrity failure (the audit chain itself is compromised) from
     * verifier infrastructure failure (the verifier could not run).
     * Reason slug format: `breaks:<N>|reason:<first-reason-code>`
     * where `<first-reason-code>` is one of the closed set emitted
     * by `audit.verify_chain()` (V9).
     */
    AUDIT_CHAIN_INTEGRITY_FAILED("audit.chain.integrity_failed"),

    /**
     * Emitted exactly once per scheduled verification cycle when the
     * verifier itself could not complete (DB unreachable, function
     * missing, permission denied, etc.). Separate from
     * [AUDIT_CHAIN_INTEGRITY_FAILED] so a compliance reviewer can
     * distinguish "chain is broken" (a clinical-safety incident)
     * from "we could not check the chain" (an infrastructure
     * incident). Reason slug format: `verifier_failed` — no
     * exception detail (Rule 01).
     */
    AUDIT_CHAIN_VERIFICATION_FAILED("audit.chain.verification_failed"),

    // --- Phase 3J: write-gate authorization denial ---
    /**
     * Emitted by every [com.medcore.platform.write.WriteAuditor]
     * implementation when [com.medcore.platform.write.AuthzPolicy.check]
     * refuses a mutation. `reason` carries the
     * [com.medcore.platform.write.WriteDenialReason.code] slug;
     * `resource_type` / `resource_id` identify what the mutation
     * targeted; no command payload leaks. Closes the 3G
     * carry-forward "audit emission on 403 access-denied."
     */
    AUTHZ_WRITE_DENIED("authz.write.denied"),

    // --- Phase 3J.2: tenancy write success ---
    /**
     * Emitted by a [com.medcore.platform.write.WriteAuditor] on the
     * success path of any `tenancy.tenant` mutation. Phase 3J.2
     * ships the first consumer: display-name updates via
     * `PATCH /api/v1/tenants/{slug}`. `reason` carries the
     * per-command intent slug (e.g., `intent:tenant.update_display_name`)
     * so sibling mutations that share this coarse action can be
     * distinguished without a schema change (ADR-007 §2.4).
     * Suppressed for no-op writes — the handler returns
     * `TenantSnapshot.changed = false`, the auditor skips emission,
     * "every persisted change emits an audit row" holds because
     * no-ops persist nothing.
     */
    TENANCY_TENANT_UPDATED("tenancy.tenant.updated"),

    // --- Phase 3J.3: membership invite ---
    /**
     * Emitted on the SUCCESS path of
     * `POST /api/v1/tenants/{slug}/memberships` when an
     * OWNER/ADMIN creates an ACTIVE membership for an existing
     * user (Phase 3J.3, ADR-007 §4.9).
     *
     * Normative audit-row shape contract is defined on
     * [com.medcore.tenancy.write.InviteTenantMembershipAuditor] —
     * successes and denials are queried by the
     * (`resource_type`, `outcome`) pair, NOT by `resource_id`
     * alone (the `resource_id` column carries different values
     * across outcome by design; see that KDoc for the canonical
     * shape + query examples).
     *
     * Naming: "invited" matches the `MEMBERSHIP_INVITE` authority
     * semantics. Phase 3J.3 is direct ACTIVE creation — email-token
     * invitation flows (PENDING lifecycle) land in a later slice
     * with their own action.
     */
    TENANCY_MEMBERSHIP_INVITED("tenancy.membership.invited"),

    // --- Phase 3J.N: membership role update + revoke ---
    /**
     * Emitted on the SUCCESS path of
     * `PATCH /api/v1/tenants/{slug}/memberships/{id}` when an
     * OWNER/ADMIN changes the role of an existing membership
     * (Phase 3J.N, ADR-007 §4.9 + §2.12 last-OWNER invariant).
     *
     * Normative audit-row shape contract is defined on
     * [com.medcore.tenancy.write.UpdateTenantMembershipRoleAuditor].
     * `reason` carries the closed-enum from/to tokens —
     * `intent:tenancy.membership.update_role|from:OWNER|to:ADMIN`
     * — so forensic reconstruction does not require joining
     * pre-change state.
     *
     * Suppressed for no-op writes (PATCH to same role), parallel
     * to [TENANCY_TENANT_UPDATED].
     */
    TENANCY_MEMBERSHIP_ROLE_UPDATED("tenancy.membership.role_updated"),

    /**
     * Emitted on the SUCCESS path of
     * `DELETE /api/v1/tenants/{slug}/memberships/{id}` when an
     * OWNER/ADMIN soft-deletes a membership (Phase 3J.N).
     *
     * Action verb matches the DB state transition
     * (`status: ACTIVE | SUSPENDED → REVOKED`). User intent
     * (`remove`) lives in the `reason` prefix. `prior_role:<X>`
     * embedded so forensic queries can isolate OWNER revocations
     * without historical joins.
     *
     * Suppressed for idempotent retries on already-REVOKED
     * memberships (handler returns `changed = false`).
     */
    TENANCY_MEMBERSHIP_REVOKED("tenancy.membership.revoked"),

    // --- Phase 4A.2: clinical patient writes (first PHI-bearing actions) ---
    /**
     * Emitted on the SUCCESS path of
     * `POST /api/v1/tenants/{slug}/patients` when an OWNER/ADMIN
     * creates a new `clinical.patient` row (Phase 4A.2, first
     * PHI-bearing action in Medcore).
     *
     * Normative shape contract lives on
     * [com.medcore.clinical.patient.write.CreatePatientAuditor]:
     *   - `actor_type` = USER
     *   - `actor_id` = caller userId
     *   - `tenant_id` = target tenant UUID
     *   - `resource_type` = `"clinical.patient"`
     *   - `resource_id` = newly-minted patient UUID
     *   - `outcome` = SUCCESS
     *   - `reason` = `intent:clinical.patient.create|mrn_source:GENERATED`
     *
     * **No PHI in the `reason` slug.** Patient name, DOB, and
     * demographic fields do NOT appear. The reason carries only
     * closed-enum tokens (`mrn_source` ∈ {GENERATED, IMPORTED}).
     * Forensic linkage to the specific patient goes through
     * `resource_id` (the UUID) — not via any identifying payload
     * in the audit row itself.
     *
     * No no-op suppression: creates are always persisted changes.
     */
    PATIENT_CREATED("clinical.patient.created"),

    /**
     * Emitted on the SUCCESS path of
     * `PATCH /api/v1/tenants/{slug}/patients/{id}` when an
     * OWNER/ADMIN updates demographic fields (Phase 4A.2).
     *
     * Normative shape contract lives on
     * [com.medcore.clinical.patient.write.UpdatePatientDemographicsAuditor]:
     *   - `resource_type` = `"clinical.patient"`
     *   - `resource_id` = target patient UUID
     *   - `reason` = `intent:clinical.patient.update_demographics|fields:<comma-sep-names>`
     *
     * **`fields` is the list of PATCH-ed field NAMES only**
     * (closed set from the DTO: `nameGiven`, `nameFamily`,
     * `birthDate`, `administrativeSex`, etc.) — never the
     * new values. Old/new value diffing waits for the Phase 7
     * audit-schema-evolution ADR (currently tracked as a
     * carry-forward).
     *
     * Suppressed for no-op writes (PATCH with every field
     * unchanged).
     */
    PATIENT_DEMOGRAPHICS_UPDATED("clinical.patient.demographics_updated"),

    // --- Phase 4A.3: patient identifier satellite writes ---
    /**
     * Emitted on the SUCCESS path of
     * `POST /api/v1/tenants/{slug}/patients/{patientId}/identifiers`
     * when an OWNER/ADMIN adds a new external identifier
     * (MRN_EXTERNAL / DRIVERS_LICENSE / INSURANCE_MEMBER / OTHER)
     * to a patient (Phase 4A.3).
     *
     * Normative shape contract lives on
     * [com.medcore.clinical.patient.write.AddPatientIdentifierAuditor]:
     *   - `actor_type` = USER
     *   - `actor_id` = caller userId
     *   - `tenant_id` = target tenant UUID
     *   - `resource_type` = `"clinical.patient.identifier"`
     *   - `resource_id` = new identifier UUID
     *   - `outcome` = SUCCESS
     *   - `reason` = `intent:clinical.patient.identifier.add|type:<IDENTIFIER_TYPE>`
     *     where `<IDENTIFIER_TYPE>` is a closed-enum token from
     *     [com.medcore.clinical.patient.model.PatientIdentifierType].
     *
     * **PHI discipline.** The `type` token in the reason is a
     * closed-enum value (4 entries: MRN_EXTERNAL / DRIVERS_LICENSE
     * / INSURANCE_MEMBER / OTHER). The `issuer` and `value`
     * fields are NEVER in the reason — `value` for DL/Insurance is
     * strongly PHI; `issuer` is implementation detail and not
     * audit-scope. Forensic resolution of the specific identifier
     * row goes through `resource_id` (the UUID), inheriting the
     * RLS envelope of `clinical.patient_identifier`.
     *
     * Add is never a no-op (an identifier is always a new row) —
     * this action is always emitted on success.
     */
    PATIENT_IDENTIFIER_ADDED("clinical.patient.identifier.added"),

    /**
     * Emitted on the SUCCESS path of
     * `DELETE /api/v1/tenants/{slug}/patients/{patientId}/identifiers/{identifierId}`
     * when an OWNER/ADMIN revokes a patient identifier
     * (Phase 4A.3).
     *
     * **Revocation is SOFT DELETE**, not row deletion. The handler
     * sets `valid_to = NOW()` on the identifier row. Audit wise,
     * the `resource_id` is still the identifier's UUID (the row
     * persists for audit integrity), and the action name
     * `revoked` captures the lifecycle transition.
     *
     * Normative shape contract lives on
     * [com.medcore.clinical.patient.write.RevokePatientIdentifierAuditor]:
     *   - `resource_type` = `"clinical.patient.identifier"`
     *   - `resource_id` = target identifier UUID
     *   - `reason` = `intent:clinical.patient.identifier.revoke|type:<IDENTIFIER_TYPE>`
     *
     * **Suppressed for no-op writes** — an idempotent DELETE on
     * an already-revoked identifier (valid_to already set)
     * returns 204 with no audit row.
     */
    PATIENT_IDENTIFIER_REVOKED("clinical.patient.identifier.revoked"),

    // --- Phase 4A.4: patient read (first PHI read audit) ---
    /**
     * Emitted on the SUCCESS path of
     * `GET /api/v1/tenants/{slug}/patients/{patientId}` when
     * the caller successfully reads a patient row (Phase
     * 4A.4, HIPAA §164.312(b) Audit Controls).
     *
     * **This is the first PHI READ audit action in Medcore.**
     * Separate from the write-audit family because reads and
     * writes are fundamentally different compliance events —
     * a forensic query "how many PHI disclosures happened?"
     * must distinguish them.
     *
     * Normative shape contract lives on
     * [com.medcore.clinical.patient.read.GetPatientAuditor]:
     *   - `actor_type`    = USER
     *   - `actor_id`      = caller userId
     *   - `tenant_id`     = target tenant UUID
     *   - `resource_type` = `"clinical.patient"`
     *   - `resource_id`   = disclosed patient UUID
     *   - `outcome`       = SUCCESS
     *   - `reason`        = `"intent:clinical.patient.access"`
     *
     * **Emission discipline (NORMATIVE):**
     *   - Emit ONLY on a 200 OK response (disclosure occurred).
     *   - Do NOT emit for 404 (no disclosure).
     *   - Do NOT emit for 500 (application bug; disclosure
     *     did not complete).
     *   - Denial paths use [AUTHZ_READ_DENIED], NOT this action.
     *
     * **Runs INSIDE the read-only transaction** (ADR-003 §2
     * extended to reads). If the audit write fails, the
     * transaction rolls back, the response body is never
     * serialised, and the caller receives 500 with no PHI
     * disclosure.
     */
    CLINICAL_PATIENT_ACCESSED("clinical.patient.accessed"),

    // --- Phase 4B.1: patient list (Vertical Slice 1, Chunk B) ---
    /**
     * Emitted on the SUCCESS path of
     * `GET /api/v1/tenants/{slug}/patients` when the caller
     * successfully lists patients in a tenant (Phase 4B.1).
     *
     * **Distinct from [CLINICAL_PATIENT_ACCESSED]** so forensic
     * review can separate "bulk enumeration events" from
     * "single-patient disclosures." A list call that returns
     * 200 items is ONE audit row, not 200.
     *
     * Normative shape contract lives on
     * [com.medcore.clinical.patient.read.ListPatientsAuditor]:
     *   - `actor_type`    = USER
     *   - `actor_id`      = caller userId
     *   - `tenant_id`     = target tenant UUID
     *   - `resource_type` = `"clinical.patient"`
     *   - `resource_id`   = null  (list has no single target row)
     *   - `outcome`       = SUCCESS
     *   - `reason`        = `"intent:clinical.patient.list|count:N|limit:L|offset:O"`
     *
     * **PHI discipline (NORMATIVE).** The reason slug carries
     * ONLY the fixed intent token + three bounded integers
     * (`count`, `limit`, `offset`). Patient UUIDs, names, MRNs,
     * and demographics NEVER appear. Forensic resolution of
     * "which rows were disclosed?" goes through `request_id`
     * → structured-log join, NOT the audit row itself.
     *
     * **Integer-in-reason precedent.** `AUDIT_CHAIN_INTEGRITY_FAILED`
     * already encodes an integer in its reason slug
     * (`breaks:<N>`). Same pattern applies here — the audit
     * envelope is deliberately flat (no JSON bag; ADR-003 §3)
     * and dynamic-but-bounded data lives in the pipe-delimited
     * reason tokens.
     *
     * **One row per list call**, including when `count = 0`.
     * A list that returns zero rows IS a disclosure event (the
     * caller learned "no matching rows exist for this view").
     *
     * **Runs INSIDE the ReadGate transaction.** Audit failure
     * rolls back; the controller never serialises the response;
     * the caller receives 500 with no PHI disclosure.
     */
    CLINICAL_PATIENT_LIST_ACCESSED("clinical.patient.list_accessed"),

    // --- Phase 4C.1: encounter shell (VS1 Chunk D) ---
    /**
     * Emitted on the SUCCESS path of
     * `POST /api/v1/tenants/{slug}/patients/{patientId}/encounters`
     * when an OWNER/ADMIN starts a new encounter (Phase 4C.1).
     *
     * First encounter write audit action in Medcore.
     *
     * Normative shape contract lives on
     * [com.medcore.clinical.encounter.write.StartEncounterAuditor]:
     *   - `actor_type`    = USER
     *   - `actor_id`      = caller userId
     *   - `tenant_id`     = target tenant UUID
     *   - `resource_type` = `"clinical.encounter"`
     *   - `resource_id`   = newly-minted encounter UUID
     *   - `outcome`       = SUCCESS
     *   - `reason`        = `"intent:clinical.encounter.start|class:AMB"`
     *
     * **No PHI in the reason slug.** Patient UUID is NOT embedded
     * (the patient is the subject, but its identity is recorded
     * through the encounter's own FK; forensic join via
     * `resource_id` → `clinical.encounter.patient_id`). `class`
     * is a closed-enum token.
     *
     * No no-op suppression: start is always a persisted INSERT.
     */
    CLINICAL_ENCOUNTER_STARTED("clinical.encounter.started"),

    /**
     * Emitted on the SUCCESS path of
     * `GET /api/v1/tenants/{slug}/encounters/{encounterId}` when
     * the caller successfully reads an encounter (Phase 4C.1).
     *
     * Normative shape contract lives on
     * [com.medcore.clinical.encounter.read.GetEncounterAuditor]:
     *   - `actor_type`    = USER
     *   - `actor_id`      = caller userId
     *   - `tenant_id`     = disclosed encounter's tenant UUID
     *   - `resource_type` = `"clinical.encounter"`
     *   - `resource_id`   = disclosed encounter UUID
     *   - `outcome`       = SUCCESS
     *   - `reason`        = `"intent:clinical.encounter.access"`
     *
     * Emission discipline mirrors `CLINICAL_PATIENT_ACCESSED`:
     * emit on 200; do NOT emit on 404 or 500; denial path uses
     * [AUTHZ_READ_DENIED]; runs INSIDE the ReadGate transaction.
     */
    CLINICAL_ENCOUNTER_ACCESSED("clinical.encounter.accessed"),

    /**
     * Emitted when a [com.medcore.platform.read.ReadAuthzPolicy]
     * refuses a read (Phase 4A.4). Sister entry to
     * [AUTHZ_WRITE_DENIED].
     *
     * Distinct entry rather than reusing `AUTHZ_WRITE_DENIED`
     * so compliance filtering can separate denied-reads from
     * denied-writes:
     *
     * ```sql
     * -- all denied PHI reads, excluding denied writes
     * SELECT * FROM audit.audit_event
     *  WHERE action = 'authz.read.denied'
     *    AND resource_type LIKE 'clinical.%';
     * ```
     *
     * Normative shape carries
     * `reason = "intent:<domain>.<resource>.access|denial:<code>"`.
     * For 4A.4:
     * `"intent:clinical.patient.access|denial:<WriteDenialReason.code>"`.
     *
     * `resource_type` = would-have-been-accessed resource type.
     * `resource_id` = URL-path target identifier (known at
     * denial time; unlike write-create denials where the
     * target UUID does not yet exist).
     */
    AUTHZ_READ_DENIED("authz.read.denied"),
}

/**
 * Who / what initiated the audited action. Matches the CHECK constraint
 * on `audit.audit_event.actor_type` in V7.
 */
enum class ActorType {
    USER,
    SYSTEM,
    SERVICE,
}

/**
 * Terminal state of the audited action. Matches the CHECK constraint on
 * `audit.audit_event.outcome` in V7.
 */
enum class AuditOutcome {
    SUCCESS,
    DENIED,
    ERROR,
}
