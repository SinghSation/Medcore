package com.medcore.architecture

import com.medcore.platform.security.phi.PhiSessionContext
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes

/**
 * Machine-enforced discipline for clinical-service PHI context
 * establishment (Phase 4A.0, ArchUnit Rule 13).
 *
 * Complements the 3I.1 rule suite with the invariant required by
 * PHI-bearing tables: every `@Transactional` method in
 * `com.medcore.clinical..service` MUST invoke
 * [PhiSessionContext.applyFromRequest] so the RLS GUCs are set
 * before any clinical SQL runs.
 *
 * Rationale: forgetting the `applyFromRequest()` call means the
 * service runs its queries with unset `app.current_user_id` and
 * `app.current_tenant_id` GUCs — every RLS policy keyed on those
 * GUCs fails closed, returning zero rows. The symptom is
 * "patient disappeared" rather than a clean error, which is
 * exactly the kind of silent-failure path compliance regimes
 * cannot tolerate.
 *
 * Until Phase 4A.1 lands, `com.medcore.clinical` packages do
 * not exist; this rule is vacuously true. The rule is placed in
 * 4A.0 so 4A.1+ services cannot land without respecting it.
 */
@AnalyzeClasses(
    packages = ["com.medcore"],
    importOptions = [com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests::class],
)
class ClinicalDisciplineArchTest {

    /**
     * RULE 13 — classes in `com.medcore.clinical..service` depend
     * on [PhiSessionContext].
     *
     * **Narrower intent:** every `@Transactional` method in
     * clinical service classes must call
     * `PhiSessionContext.applyFromRequest()` as its first line.
     *
     * **Expressible approximation:** any class in
     * `clinical..service` must have a dependency on
     * [PhiSessionContext] somewhere in its source. In practice
     * this means the class must `@Autowire` / inject the bean,
     * which is the dev-time artifact of actually calling it —
     * the injection only makes sense if the class calls the
     * bean. Forgetting the call also means forgetting the
     * injection, which this rule catches.
     *
     * **Failure mode this prevents:** a clinical service class
     * that opens a `@Transactional` method but forgets to call
     * `applyFromRequest()` would run with unset RLS GUCs, and
     * every subsequent query would return zero rows — silent
     * PHI-access failure, exactly the forensic nightmare this
     * rule guards against.
     *
     * **Vacuously true in 4A.0:** no `..clinical..service..`
     * packages exist yet. The rule is placed now so 4A.1+
     * services cannot land without wiring in
     * [PhiSessionContext].
     */
    @ArchTest
    val clinical_service_classes_depend_on_phi_session_context: ArchRule =
        classes()
            .that().resideInAPackage("..clinical..service..")
            .should().dependOnClassesThat().areAssignableTo(PhiSessionContext::class.java)
            // allowEmptyShould — vacuously true in 4A.0 (no clinical
            // service classes exist yet). ArchUnit's default is to
            // fail on empty check sets to catch typo'd package
            // filters; this rule is DELIBERATELY written before its
            // first consumer lands, so the empty-set case is expected
            // until 4A.1 introduces the patient service. Remove the
            // allowance once a real clinical service lands (at which
            // point an empty set WOULD mean the filter is wrong).
            .allowEmptyShould(true)
            .`as`(
                "Classes in com.medcore.clinical..service MUST depend " +
                    "on PhiSessionContext (the dev-time artifact of " +
                    "calling applyFromRequest as the first line of " +
                    "every @Transactional clinical method). Forgetting " +
                    "this means RLS GUCs are not set for the " +
                    "transaction, and clinical queries return zero " +
                    "rows silently. See ADR-007 §7 PhiRlsTxHook " +
                    "carry-forward + 4A.0 design refinement #3.",
            )
}
