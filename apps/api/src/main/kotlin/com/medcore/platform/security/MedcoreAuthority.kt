package com.medcore.platform.security

import org.springframework.security.core.GrantedAuthority

/**
 * Closed enum of Medcore authorities (Phase 3J, ADR-007 §4.9).
 *
 * Each entry wraps a stable Spring `GrantedAuthority` string
 * (`"MEDCORE_TENANT_UPDATE"`, etc.) used by method security
 * expressions and `AuthzPolicy` implementations.
 *
 * **Granular by design.** Refinement #5 from the Phase 3J pressure
 * test split the coarse "MANAGE" authorities into specific actions
 * preemptively so future DELETE endpoints (and everything else)
 * don't force authority renames — renaming a shipped authority is
 * a breaking security-contract change.
 *
 * **Registry discipline** (ADR-005 §2.3 pattern):
 *   1. New entry here.
 *   2. Update `MembershipRoleAuthorities` map.
 *   3. Update `MembershipRoleAuthoritiesTest`.
 *   4. Review-pack callout.
 *   5. Renaming/removing requires a superseding ADR — same
 *      discipline as `ErrorCodes`, `AuditAction`, and the Phase
 *      3F.2 span-attribute allow list.
 */
enum class MedcoreAuthority(val role: String) : GrantedAuthority {
    // --- Tenancy authorities ---
    TENANT_READ("MEDCORE_TENANT_READ"),
    TENANT_UPDATE("MEDCORE_TENANT_UPDATE"),
    TENANT_DELETE("MEDCORE_TENANT_DELETE"),

    // --- Membership authorities ---
    MEMBERSHIP_READ("MEDCORE_MEMBERSHIP_READ"),
    MEMBERSHIP_INVITE("MEDCORE_MEMBERSHIP_INVITE"),
    MEMBERSHIP_ROLE_UPDATE("MEDCORE_MEMBERSHIP_ROLE_UPDATE"),
    MEMBERSHIP_REMOVE("MEDCORE_MEMBERSHIP_REMOVE"),

    // --- Patient (clinical) authorities (Phase 4A.1) ---
    // Role map (4A.1, documented simplification):
    //   OWNER + ADMIN — all three (read/create/update)
    //   MEMBER        — READ only
    // Clinical role differentiation (CLINICIAN / NURSE / STAFF)
    // with finer PATIENT_* grants is a dedicated future slice
    // when a pilot clinic demands it. PATIENT_MERGE is reserved
    // for the merge-workflow slice — NOT in 4A.1.
    PATIENT_READ("MEDCORE_PATIENT_READ"),
    PATIENT_CREATE("MEDCORE_PATIENT_CREATE"),
    PATIENT_UPDATE("MEDCORE_PATIENT_UPDATE"),

    // --- Encounter (clinical) authorities (Phase 4C.1, VS1 Chunk D) ---
    // Role map (4C.1, documented simplification):
    //   OWNER + ADMIN — ENCOUNTER_READ + ENCOUNTER_WRITE
    //   MEMBER        — ENCOUNTER_READ only
    // Deliberately coarse. State-transitions (FINISHED / CANCELLED)
    // and provider-attribution grants are future Phase 4C slices
    // that may split ENCOUNTER_WRITE into CREATE / UPDATE / FINISH
    // / CANCEL at that time.
    ENCOUNTER_READ("MEDCORE_ENCOUNTER_READ"),
    ENCOUNTER_WRITE("MEDCORE_ENCOUNTER_WRITE"),

    // --- Clinical-note authorities (Phase 4D.1 + 4D.5 + 4D.6) ---
    // Role map:
    //   OWNER + ADMIN — NOTE_READ + NOTE_WRITE + NOTE_SIGN
    //   MEMBER        — NOTE_READ only
    //
    // NOTE_SIGN is deliberately separate from NOTE_WRITE (4D.5):
    // in real clinical role models, a trainee / nurse may write
    // notes but only a physician may sign them. Splitting now
    // keeps future clinical-role differentiation additive —
    // renaming a shipped authority is a breaking security-contract
    // change (Rule 07 forward-only). For MVP, both OWNER and
    // ADMIN hold NOTE_SIGN; MEMBER does not.
    //
    // Amendments (Phase 4D.6) reuse NOTE_WRITE per the locked
    // 4D.6 plan (Q3) — a separate NOTE_AMEND authority would only
    // be introduced when a clinical-role policy demands distinct
    // gating (e.g., "only physicians may amend").
    NOTE_READ("MEDCORE_NOTE_READ"),
    NOTE_WRITE("MEDCORE_NOTE_WRITE"),
    NOTE_SIGN("MEDCORE_NOTE_SIGN"),

    // --- Allergy authorities (Phase 4E.1) ---
    // Role map:
    //   OWNER + ADMIN — ALLERGY_READ + ALLERGY_WRITE
    //   MEMBER        — ALLERGY_READ only
    //
    // First longitudinal patient-level dataset. Same shape as
    // ENCOUNTER_* and NOTE_* — read for everyone, write gated to
    // tenant-admin roles. ALLERGY_WRITE is deliberately a
    // distinct authority (NOT a reuse of PATIENT_UPDATE) per the
    // locked 4E.1 plan (Q6): clinical safety data has its own
    // operational lifecycle (deactivation, ENTERED_IN_ERROR
    // workflow) and a separate authority avoids a future RBAC
    // refactor when that lifecycle deepens.
    ALLERGY_READ("MEDCORE_ALLERGY_READ"),
    ALLERGY_WRITE("MEDCORE_ALLERGY_WRITE"),

    // --- System-scope (bootstrap, admin ops) ---
    /**
     * Reserved for bootstrap / admin operations that must bypass
     * normal tenancy scoping — specifically, creating the FIRST
     * tenant before any tenant exists for the caller to be a
     * member of. No standard role grants this authority. Acquired
     * only via bootstrap IdP claim, elevated session, or
     * migrator-only DB path. See ADR-007 §4.5.
     */
    SYSTEM_WRITE("MEDCORE_SYSTEM_WRITE"),
    ;

    override fun getAuthority(): String = role
}
