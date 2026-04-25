package com.medcore.clinical.problem.model

/**
 * FHIR-aligned problem severity (Phase 4E.2).
 *
 * Closed enum — matches the DB CHECK constraint
 * `ck_clinical_problem_severity` added in V25. Adding a new
 * severity requires a superseding ADR + migration.
 *
 * **Distinct from [com.medcore.clinical.allergy.model.AllergySeverity]**
 * by design: FHIR keeps `Condition.severity` and
 * `AllergyIntolerance.criticality` in separate value sets. We
 * mirror that separation at the type level so a future
 * clinical-role policy that gates problem-severity changes
 * differently from allergy-criticality changes never has to
 * un-merge a shared enum. Tokens overlap (MILD / MODERATE /
 * SEVERE) but the type identity does not.
 *
 * **Nullable column.** Many problems have no clinically
 * meaningful severity — "history of appendectomy", "smoking
 * cessation goal", historical entries. The DB column is
 * NULLable per locked Q3 of the 4E.2 plan, and Kotlin call
 * sites use `ProblemSeverity?` accordingly. There is no
 * "UNKNOWN" sentinel — `null` IS the unknown / unspecified
 * value (cleaner than introducing a sentinel that some code
 * paths might forget to filter).
 *
 * Phase 5A FHIR-mapping will project these onto the FHIR R4
 * `Condition.severity` value set (likely MILD → SNOMED
 * `255604002`, MODERATE → `6736007`, SEVERE → `24484000`).
 */
enum class ProblemSeverity {
    MILD,
    MODERATE,
    SEVERE,
}
