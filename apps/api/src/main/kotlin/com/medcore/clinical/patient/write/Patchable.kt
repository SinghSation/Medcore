package com.medcore.clinical.patient.write

/**
 * Three-state wrapper for fields in a PATCH command (Phase 4A.2).
 *
 * RFC 7396 JSON Merge Patch semantics with explicit null
 * discrimination:
 *
 *   - [Absent] — the client did not send the field in the JSON body.
 *     The handler MUST leave the target column unchanged. Default
 *     state for every field on a freshly-constructed command.
 *   - [Clear] — the client sent the field with the JSON value `null`.
 *     The handler MUST write NULL to the column. Only legal on
 *     nullable columns; the validator refuses [Clear] on required
 *     columns (e.g., `nameGiven`) with error code `required`.
 *   - [Set] — the client sent the field with a non-null JSON value.
 *     The handler MUST write the wrapped value.
 *
 * ### Why a sealed hierarchy instead of `Optional<Optional<T>>`
 *
 * `Optional<T>` cannot distinguish "field absent" from "field
 * present with null" — `Optional.empty()` collapses both cases.
 * The sealed class above makes the three states type-distinct, so
 * a handler visitor has to handle each explicitly (no silent
 * miscategorisation).
 *
 * ### Why not Kotlin's `null` directly
 *
 * `val nameGiven: String? = null` cannot distinguish "caller sent
 * null" from "caller omitted the field" — both materialise as
 * `null`. The [Patchable] wrapper carries the presence/absence
 * distinction separately from the value's nullability.
 *
 * ### Deserialisation
 *
 * Jackson does not natively understand this hierarchy. The
 * [com.medcore.clinical.patient.api.PatientController] PATCH
 * handler reads the request body as a `JsonNode`, enumerates the
 * known patchable fields, and builds an
 * [UpdatePatientDemographicsCommand] with the appropriate
 * [Patchable] for each field based on node presence/null.
 */
sealed class Patchable<out T> {
    data object Absent : Patchable<Nothing>()
    data object Clear : Patchable<Nothing>()
    data class Set<T>(val value: T) : Patchable<T>()

    /**
     * Convenience: returns the wrapped value on [Set], null on
     * [Clear], and invokes [ifAbsent] on [Absent] (typically the
     * target row's existing value for that column).
     */
    inline fun <R> resolve(ifAbsent: () -> R, whenClear: R, whenSet: (T) -> R): R =
        when (this) {
            Absent -> ifAbsent()
            Clear -> whenClear
            is Set -> whenSet(value)
        }
}
