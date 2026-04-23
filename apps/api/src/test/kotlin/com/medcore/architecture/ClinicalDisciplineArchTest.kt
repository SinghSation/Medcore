package com.medcore.architecture

import com.medcore.platform.security.phi.PhiSessionContext
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.springframework.stereotype.Component

/**
 * Machine-enforced discipline for clinical-service PHI context
 * establishment (ArchUnit Rule 13 — activated in Phase 4A.2).
 *
 * Complements the 3I.1 rule suite with the invariant required by
 * PHI-bearing paths: every class in `com.medcore.clinical..service`
 * MUST depend on [PhiSessionContext] — the dev-time artefact of
 * calling `applyFromRequest()` in any PHI-touching method (see
 * Phase 4A.0 design refinement #3).
 *
 * ### Activation history
 *
 *   - **4A.0** — rule placed with `.allowEmptyShould(true)` so
 *     the invariant is in the codebase before any clinical
 *     service class lands.
 *   - **4A.1** — no clinical service class yet; allowance stays.
 *   - **4A.2** — [com.medcore.clinical.patient.service.DuplicatePatientDetector]
 *     lands as the first `..clinical..service..` class and
 *     depends on [PhiSessionContext] (calls
 *     `applyFromRequest()` as a defensive context check before
 *     issuing PHI-touching SELECTs). Rule 13 is now **ACTIVE**
 *     — `.allowEmptyShould(true)` removed. Any future clinical
 *     service class that forgets the [PhiSessionContext]
 *     dependency fails this rule at CI time, preventing the
 *     silent-zero-row bug class entirely.
 *
 * ### Rationale
 *
 * Forgetting the `applyFromRequest()` call means a clinical
 * service runs its queries with unset `app.current_user_id` +
 * `app.current_tenant_id` GUCs — every RLS policy keyed on those
 * GUCs fails closed and returns zero rows. The symptom is
 * "patient disappeared", which is exactly the kind of silent-
 * failure path compliance regimes cannot tolerate.
 *
 * The ArchUnit check is an approximation: it verifies the
 * DEPENDENCY, not the call. In practice the dependency only
 * makes sense if the class calls the bean, so the
 * approximation catches the overwhelmingly common drift
 * (developer forgets both injection AND call).
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
     * **ACTIVE as of Phase 4A.2** (no longer empty-allowed).
     */
    @ArchTest
    val clinical_service_classes_depend_on_phi_session_context: ArchRule =
        classes()
            .that().resideInAPackage("..clinical..service..")
            // Narrow to classes that actually execute logic. Exception
            // types + data-class DTOs live in the service package
            // (they travel alongside the service's API surface) but
            // are NOT callers of PhiSessionContext. The rule's intent
            // is to catch *logic* classes that forget the PHI-context
            // call — Spring `@Component`s are the structural proxy
            // for that (a class that runs real work is always a
            // Spring bean in Medcore's conventions).
            .and().areAnnotatedWith(Component::class.java)
            .should().dependOnClassesThat().areAssignableTo(PhiSessionContext::class.java)
            .`as`(
                "@Component classes in com.medcore.clinical..service " +
                    "MUST depend on PhiSessionContext (the dev-time " +
                    "artifact of calling applyFromRequest in any PHI- " +
                    "touching method). Forgetting this means RLS GUCs " +
                    "are not set for the transaction, and clinical " +
                    "queries return zero rows silently. See ADR-007 §7 " +
                    "PhiRlsTxHook carry-forward + 4A.0 design " +
                    "refinement #3 + 4A.2 activation notes.",
            )
}
