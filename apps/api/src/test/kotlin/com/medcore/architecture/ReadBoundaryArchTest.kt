package com.medcore.architecture

import com.medcore.platform.read.ReadGate
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Read-path boundary rules (Phase 4A.4). Sister to
 * [MutationBoundaryArchTest] (Rule 12 for writes).
 *
 * ### Why a new file, not an extension of MutationBoundary
 *
 * `MutationBoundaryArchTest` is named for write-side rules.
 * Adding read-side rules there would either require a rename
 * (churn) or misleading naming. A sibling test file keeps
 * each file's concept focused.
 */
@AnalyzeClasses(
    packages = ["com.medcore"],
    importOptions = [com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests::class],
)
class ReadBoundaryArchTest {

    /**
     * RULE 14 — `ReadGate.apply(...)` is invoked ONLY from
     * `.api` packages (controllers). Sister to Rule 12 for
     * writes.
     *
     * The invariant: controllers are the sole LEGITIMATE entry
     * point into the read pipeline. A service, filter, or
     * handler calling `ReadGate.apply` directly bypasses the
     * controller-layer request shaping (principal resolution,
     * MDC, response envelope, tenant-context filter ordering,
     * etc.) and introduces a second entry point that governance
     * would have to track separately.
     *
     * Construction of the gate (from `PatientWriteConfig`) is
     * NOT a call to `apply` — it's allowed.
     *
     * Allowlist mirrors Rule 12's shape:
     *   - `..api..` — controllers (production callers)
     *   - `..read..` — ReadGate's own tests live adjacent
     *     (same test-adjacency exception as Rule 12 gives
     *     `..write..`)
     */
    @ArchTest
    val read_gate_apply_called_only_from_api: ArchRule =
        methods()
            .that().areDeclaredIn(ReadGate::class.java)
            .and().haveName("apply")
            .should().onlyBeCalled().byClassesThat().resideInAnyPackage(
                "..api..",
                "..read..", // ReadGate tests live adjacent; see §9 file map
            )
            .`as`(
                "ReadGate.apply is invoked only from controller (.api) " +
                    "packages. Services, filters, auditors, and policies " +
                    "must not open a second read entry point. Sister to " +
                    "Rule 12 for writes; clinical-write-pattern v1.2 §12 " +
                    "codifies this.",
            )

    /**
     * RULE 15 — every `*ListResponse` DTO in `..api..` MUST
     * declare a `pageInfo` field, signalling adoption of the
     * platform pagination substrate (ADR-009). Exceptions live
     * in [LEGACY_UNPAGINATED_LIST_RESPONSES] — that allowlist
     * shrinks across chunks B–E as each endpoint migrates.
     *
     * **Goal**: structurally prevent a future contributor from
     * adding a new unpaginated list endpoint. A new
     * `*ListResponse` class without `pageInfo` and without an
     * allowlist entry breaks this test loudly.
     *
     * **Allowlist policy**:
     *   - `PatientListResponse` — uses offset-based pagination
     *     under a different scheme (`{items, totalCount, limit,
     *     offset, hasMore}`), shipped pre-substrate. ADR-009
     *     §1 explicitly leaves it alone unless clean unification
     *     is trivial; until then it's a documented exception.
     *   - Others: progressively migrated. As of chunk B,
     *     `EncounterNoteListResponse` is paginated;
     *     `AllergyListResponse`, `EncounterListResponse`,
     *     `ProblemListResponse` are still on the allowlist
     *     and migrate in chunks C/D/E.
     *
     * Implemented as a JUnit test (rather than an ArchUnit
     * `@ArchTest` rule) because field-existence checks are
     * cleaner via reflection on imported classes than via the
     * fluent `classes()` DSL.
     */
    @Test
    fun `RULE 15 — every paginated *ListResponse declares a pageInfo field`() {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.medcore")
            .filter { jc ->
                jc.simpleName.endsWith("ListResponse") &&
                    jc.packageName.contains(".api") &&
                    jc.simpleName !in LEGACY_UNPAGINATED_LIST_RESPONSES
            }

        // Sanity check — there should be at least one paginated
        // class for the test to be meaningful. As of chunk B
        // that's `EncounterNoteListResponse`. If this assertion
        // fails after chunks C–E, it means every list class is
        // somehow on the legacy allowlist — a regression.
        assertThat(classes)
            .describedAs(
                "At least one paginated *ListResponse class must " +
                    "exist (chunk B paginated EncounterNoteListResponse). " +
                    "If this assertion fails, the substrate is not yet " +
                    "applied to any endpoint.",
            )
            .isNotEmpty

        classes.forEach { jc ->
            val fieldNames = jc.fields.map { it.name }
            assertThat(fieldNames)
                .describedAs(
                    "${jc.simpleName} (in ${jc.packageName}) must declare " +
                        "a 'pageInfo' field. ADR-009 / Rule 15. If this " +
                        "class intentionally uses a different pagination " +
                        "scheme, add it to LEGACY_UNPAGINATED_LIST_RESPONSES " +
                        "with rationale.",
                )
                .contains("pageInfo")
        }
    }

    private companion object {
        /**
         * `*ListResponse` classes exempt from Rule 15. Each entry
         * is a documented exception; new entries require an ADR.
         *
         * Shrinks across chunks B–E as endpoints migrate to the
         * substrate. After chunk E, only `PatientListResponse`
         * remains.
         */
        val LEGACY_UNPAGINATED_LIST_RESPONSES: Set<String> = setOf(
            // Pre-substrate offset-based pagination; intentional
            // exception per ADR-009 §1.
            "PatientListResponse",
            // Tenancy memberships are not in the longitudinal-
            // clinical-list scope of ADR-009. A single user's
            // membership list is bounded by tenant count
            // (intuitively single-digits, hard-bounded by SaaS
            // tenant-onboarding policy). If this changes, a
            // future slice can paginate it under the substrate.
            "MembershipListResponse",
            // Migrating in chunk D.
            "AllergyListResponse",
            // Migrating in chunk E.
            "ProblemListResponse",
        )
    }
}
