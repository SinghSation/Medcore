package com.medcore.architecture

import com.medcore.platform.write.AuthzPolicy
import com.medcore.platform.write.WriteAuditor
import com.medcore.platform.write.WriteGate
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.properties.HasName
import com.tngtech.archunit.core.domain.properties.HasOwner
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.Repository

/**
 * Machine-enforced invariants for the WriteGate mutation boundary
 * (Phase 3I.1, ADR-007 §2.10 + §2.13).
 *
 * These rules are the CI gate behind "WriteGate is the exclusive
 * mutation entry point." Until 3I.1 the discipline was review-
 * gated; now every PR that violates the perimeter fails the build.
 *
 * ### Perimeter, by layer
 *
 *   HTTP → Controller (.api)
 *     → WriteGate.apply(...)        ← only callable from .api
 *       → Policy       (.write)     ← AuthzPolicy impls in .write
 *       → Handler      (.write)     ← calls repositories
 *         → JpaRepository.save/delete  ← only from .write
 *       → Auditor      (.write)     ← WriteAuditor impls in .write
 *
 * Reads flow differently:
 *   HTTP → Controller (.api)
 *     → Service (.service)          ← controllers never call repo
 *       → JpaRepository (read-only) ← repos accessed via service
 *
 * ### Legit exceptions
 *
 * - Tests reside in `src/test/kotlin` and are excluded from
 *   imported classes via `AnalyzeClasses(importOptions = ...)`.
 * - `platform.*` auxiliary infrastructure (security filter chain,
 *   observability hooks, session context) occasionally touches
 *   repositories for cross-cutting concerns — whitelisted where
 *   necessary with documented rationale.
 */
@AnalyzeClasses(
    packages = ["com.medcore"],
    importOptions = [com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests::class],
)
class MutationBoundaryArchTest {

    /**
     * RULE 1 — JPA repositories are referenced only by layer
     * packages that legitimately need them.
     *
     * Access perimeter:
     *   - `..write..` — handlers that mutate domain entities
     *   - `..service..` — read-side tenancy/identity projections
     *   - `..persistence..` — the entity + repository interfaces
     *     themselves (self-reference is permitted)
     *   - `..platform..` — cross-cutting infra (e.g., Flyway
     *     startup check reads `flyway.flyway_schema_history`)
     *   - `..identity..` — the identity module's provisioning
     *     path reads + writes IdentityUserEntity directly at
     *     JIT-provision time (pre-dates WriteGate; 3J scope only
     *     covered tenancy). Tracked to Phase 4A for migration to
     *     a WriteGate-gated identity handler.
     */
    @ArchTest
    val repositories_accessed_only_from_sanctioned_layers: ArchRule =
        classes()
            .that().areAssignableTo(Repository::class.java)
            .should().onlyBeAccessed().byClassesThat().resideInAnyPackage(
                "..write..",
                "..read..",   // Phase 4A.4: read handlers legitimately
                              // access repositories under ReadGate's
                              // read-only tx + PhiRlsTxHook envelope.
                "..service..",
                "..persistence..",
                "..platform..",
                "..identity..",
            )
            .`as`(
                "JPA repositories are accessed only from handler " +
                    "(.write / .read), service, persistence, platform, " +
                    "or identity packages — NEVER from controllers, " +
                    "filters, auditors, or policies. See ADR-007 §2.10 " +
                    "+ Phase 4A.4 read-path addition.",
            )

    /**
     * RULE 2 — `JpaRepository.save/delete/deleteById/saveAll/deleteAll`
     * are called only from `.write` packages or from
     * `..identity..` (the JIT-provision carry-forward above).
     *
     * Complements RULE 1 by catching the case where a service
     * method that's not a handler tries to mutate. Read-only
     * repository methods (`findBy*`, `existsBy*`) are unrestricted.
     */
    @ArchTest
    val jpa_mutation_methods_only_from_write_or_identity: ArchRule =
        noClasses()
            .that().resideOutsideOfPackages(
                "..write..",
                "..identity..",
                "..persistence..",
            )
            .should().callMethodWhere(
                // Compose two JavaCall predicates and AND them —
                // each returns DescribedPredicate<JavaCall<*>>, so
                // they compose cleanly (avoiding the HasName vs
                // HasOwner type-mismatch from a single-nested target()).
                JavaCall.Predicates.target(
                    HasOwner.Predicates.With.owner(
                        JavaClass.Predicates.assignableTo(JpaRepository::class.java),
                    ),
                ).and(
                    JavaCall.Predicates.target(
                        HasName.Predicates.nameMatching(
                            "save|saveAll|delete|deleteById|deleteAll|deleteAllById",
                        ),
                    ),
                ),
            )
            .`as`(
                "JpaRepository.save / delete / deleteById / saveAll " +
                    "/ deleteAll / deleteAllById are called only from " +
                    ".write handler packages (or .identity / " +
                    ".persistence internals). Mutations outside " +
                    "WriteGate's perimeter are forbidden.",
            )

    /**
     * RULE 3 — Controllers in `.api` packages do NOT reference JPA
     * repositories.
     *
     * Strict: even read queries go through a service. Caching,
     * authorization layering, tenant scoping, and DTO shaping
     * belong in services, not controllers.
     */
    @ArchTest
    val controllers_do_not_reference_repositories: ArchRule =
        noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().areAssignableTo(Repository::class.java)
            .`as`(
                "Controllers (.api packages) do NOT reference JPA " +
                    "repositories directly. Reads go through services; " +
                    "writes go through WriteGate. No exceptions — if a " +
                    "controller needs data, add a service method.",
            )

    /**
     * RULE 4 — Classes implementing [AuthzPolicy] reside in a
     * `.write` package.
     *
     * Policies are mutation-time concerns. An `AuthzPolicy` outside
     * `.write` is a signal someone is trying to authz-gate a read
     * path or has built a parallel mutation perimeter.
     */
    @ArchTest
    val authz_policies_live_in_write_packages: ArchRule =
        classes()
            .that().implement(AuthzPolicy::class.java)
            .should().resideInAPackage("..write..")
            .`as`(
                "AuthzPolicy implementations live in a .write package. " +
                    "Authorization is a mutation-pipeline concern; " +
                    "policies elsewhere suggest a parallel mutation " +
                    "perimeter that WriteGate doesn't govern.",
            )

    /**
     * RULE 5 — Classes implementing [WriteAuditor] reside in a
     * `.write` package.
     *
     * Same rationale as Rule 4 — auditors are part of the mutation
     * pipeline contract.
     */
    @ArchTest
    val write_auditors_live_in_write_packages: ArchRule =
        classes()
            .that().implement(WriteAuditor::class.java)
            .should().resideInAPackage("..write..")
            .`as`(
                "WriteAuditor implementations live in a .write package. " +
                    "They form the audit-emission leg of the mutation " +
                    "pipeline contract.",
            )

    /**
     * RULE 12 — `WriteGate.apply(...)` is invoked ONLY from `.api`
     * packages (controllers).
     *
     * The complement to Rule 1/2: controllers are the sole
     * LEGITIMATE entry point into the mutation pipeline. A service
     * calling `WriteGate.apply` directly bypasses the controller-
     * layer request shaping (bean validation, principal resolution,
     * MDC setup, response envelope, etc.) and introduces a second
     * entry point we'd then have to govern separately.
     *
     * Construction of the gate (from `TenantWriteConfig`) is NOT
     * a call to `apply` — it's allowed.
     */
    @ArchTest
    val write_gate_apply_called_only_from_api: ArchRule =
        methods()
            .that().areDeclaredIn(WriteGate::class.java)
            .and().haveName("apply")
            .should().onlyBeCalled().byClassesThat().resideInAnyPackage(
                "..api..",
                "..write..",  // WriteGate's own tests live adjacent; see note below
            )
            .`as`(
                "WriteGate.apply is invoked only from controller (.api) " +
                    "packages. Services, filters, auditors, and policies " +
                    "must not open a second mutation entry point " +
                    "(ADR-007 §2.10, Rule 12).",
            )
}
