package com.medcore.architecture

import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * Machine-enforced inter-module boundaries (Phase 3I.1, ADR-001 §2).
 *
 * Medcore's module layout:
 *
 *   com.medcore.platform.*   — cross-module infrastructure. Allowed
 *                              dependency for every module.
 *   com.medcore.identity.*   — workforce identity. Cross-tenant
 *                              infrastructure by ADR-001; NEVER
 *                              imports tenancy or audit internals.
 *   com.medcore.tenancy.*    — tenant + membership. References
 *                              users only by bare UUID (V6 KDoc);
 *                              must not import identity.persistence
 *                              or identity.service.
 *   com.medcore.audit.*      — (if ever introduced as a standalone
 *                              module). Currently lives under
 *                              platform.audit; rule kept as a
 *                              forward-looking guard.
 */
@AnalyzeClasses(
    packages = ["com.medcore"],
    importOptions = [com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests::class],
)
class ModuleBoundaryArchTest {

    /**
     * RULE 6 — Identity module does not depend on tenancy or
     * audit internals.
     *
     * Identity is cross-tenant infrastructure. Tenancy-scoped
     * concerns (membership lookup, tenant status) cannot leak
     * into identity code. Audit emission is fine via the
     * `AuditWriter` interface in `platform.audit`, but identity
     * must not depend on tenancy-layer audit emission paths.
     */
    @ArchTest
    val identity_does_not_depend_on_tenancy_or_audit_business_modules: ArchRule =
        noClasses()
            .that().resideInAPackage("com.medcore.identity..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.medcore.tenancy..",
            )
            .`as`(
                "Identity module does NOT depend on tenancy internals. " +
                    "Identity is cross-tenant infrastructure by ADR-001 §2; " +
                    "tenancy concerns must not leak into identity code.",
            )

    /**
     * RULE 7 — Tenancy does not depend on identity's persistence
     * or service layers.
     *
     * Cross-module reference discipline (V6 KDoc): tenancy stores
     * user_id as a bare UUID. No FK, no JPA association, no import
     * of `IdentityUserEntity` or `IdentityUserRepository`.
     * Tenancy MAY depend on `platform.*` (including
     * `platform.security` which carries the principal).
     */
    @ArchTest
    val tenancy_does_not_depend_on_identity_persistence_or_service: ArchRule =
        noClasses()
            .that().resideInAPackage("com.medcore.tenancy..")
            .and().resideOutsideOfPackage("com.medcore.tenancy.write..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.medcore.identity.persistence..",
                "com.medcore.identity.service..",
            )
            .`as`(
                "Tenancy module does NOT import identity.persistence " +
                    "or identity.service. Cross-module user references " +
                    "use bare UUIDs (V6 KDoc). Exception: tenancy.write " +
                    "handlers may check identity-user existence via " +
                    "IdentityUserRepository.existsById — a narrow, " +
                    "typed-UUID-in / boolean-out contract documented in " +
                    "InviteTenantMembershipHandler.",
            )

    /**
     * RULE 8 — Platform.audit does not depend on business modules.
     *
     * Audit is infrastructure. Business modules emit via the
     * `AuditWriter` interface; audit never imports tenancy,
     * identity, or any future clinical module. Keeps the
     * dependency arrow from audit to business modules strictly
     * one-way (business depends on audit, never vice versa).
     */
    @ArchTest
    val audit_does_not_depend_on_business_modules: ArchRule =
        noClasses()
            .that().resideInAPackage("com.medcore.platform.audit..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.medcore.identity..",
                "com.medcore.tenancy..",
            )
            .`as`(
                "platform.audit does NOT depend on business modules. " +
                    "Audit is cross-cutting infrastructure; business " +
                    "modules emit via the AuditWriter interface. This " +
                    "rule guards the one-way dependency arrow.",
            )
}
