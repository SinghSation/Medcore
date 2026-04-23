package com.medcore.clinical.patient.mrn

/**
 * Closed set of MRN formatting strategies (Phase 4A.2).
 *
 * Stored as TEXT on `clinical.patient_mrn_counter.format_kind`
 * (V15 migration); CHECK constraint restricts values to this
 * enum's names. Mirrors the [com.medcore.platform.security.MedcoreAuthority]
 * pattern — closed enum + wire value + registry discipline.
 *
 * ### Current values (4A.2)
 *
 * - [NUMERIC] — `next_value` zero-padded to `width`, prefixed
 *   with `prefix`. E.g., `prefix="", width=6, next=42` →
 *   `"000042"`.
 *
 * ### Future slices (documented, NOT implemented)
 *
 * - `ALPHANUMERIC` — base-36 / base-62 encodings for compact
 *   MRNs when a pilot clinic has legacy integrations that
 *   expect 4-5 character identifiers.
 * - `CHECK_DIGIT_MOD10` — Luhn-style check digit appended to
 *   the numeric value; useful when MRNs are read aloud /
 *   keyed in from paper charts.
 *
 * Adding a new format kind is ADDITIVE:
 *   1. New enum entry here.
 *   2. V-migration extending the `ck_clinical_patient_mrn_counter_format_kind`
 *      CHECK constraint (drop + recreate with wider set).
 *   3. New branch in [MrnGenerator.format].
 *   4. Tests for the new branch.
 *
 * Renaming or removing a format kind is a breaking wire-contract
 * change (MRNs are durable identifiers stored on patient rows
 * forever) — superseding ADR required.
 */
enum class MrnFormatKind {
    NUMERIC,
}
