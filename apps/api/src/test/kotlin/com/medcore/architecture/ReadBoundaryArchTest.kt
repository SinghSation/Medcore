package com.medcore.architecture

import com.medcore.platform.read.ReadGate
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods

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
}
