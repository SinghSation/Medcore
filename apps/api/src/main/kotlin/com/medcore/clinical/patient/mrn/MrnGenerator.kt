package com.medcore.clinical.patient.mrn

import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Mints per-tenant Medical Record Numbers (MRNs) for the
 * `clinical.patient` GENERATED path (Phase 4A.2).
 *
 * ### Concurrency contract
 *
 * Uses a single atomic statement:
 *
 * ```
 * INSERT INTO clinical.patient_mrn_counter (...) VALUES (?, ...)
 * ON CONFLICT (tenant_id) DO UPDATE
 *   SET next_value  = clinical.patient_mrn_counter.next_value + 1,
 *       updated_at  = ?,
 *       row_version = clinical.patient_mrn_counter.row_version + 1
 * RETURNING next_value, prefix, width, format_kind;
 * ```
 *
 * Two concurrent transactions targeting the same tenant serialise
 * via Postgres's `ON CONFLICT` handling: the second INSERT loses,
 * flips to DO UPDATE, and reads the value the first committed.
 * No explicit `FOR UPDATE` lock needed; Postgres's uniqueness
 * machinery is the serialisation primitive.
 *
 * ### Rollback safety (NORMATIVE — `MrnRollbackTest` asserts)
 *
 * [generate] MUST run inside the caller's transaction (typically
 * the `WriteGate`-owned tx of a patient-create handler). If that
 * transaction aborts, the counter bump rolls back atomically —
 * the MRN is never "consumed". This is the reason the generator
 * does NOT open its own transaction.
 *
 * ### Format extensibility (Phase 4A.2 design-pack refinement)
 *
 * The generator does NOT hardcode numeric formatting. The counter
 * row carries `format_kind`; [format] dispatches on it. Future
 * kinds (`ALPHANUMERIC`, `CHECK_DIGIT_MOD10`) land as additive
 * branches. See [MrnFormatKind] KDoc.
 *
 * ### Input assumptions
 *
 * - Caller has set the RLS GUCs (`app.current_tenant_id` +
 *   `app.current_user_id`) via [com.medcore.platform.security.phi.PhiSessionContext]
 *   BEFORE invoking [generate]. The V15 RLS policies require both;
 *   without them the upsert returns zero rows and the generator
 *   throws [MrnGenerationException].
 * - Caller holds `PATIENT_CREATE` authority (OWNER/ADMIN membership).
 *   V15's INSERT/UPDATE policies gate on role — if the caller lacks
 *   it, the upsert violates WITH CHECK and Postgres throws.
 *
 * ### Absent from 4A.2 (documented carry-forwards)
 *
 * - **IMPORTED MRN path** — ships in a later slice with its own
 *   collision-retry logic against `uq_clinical_patient_tenant_mrn`.
 * - **Tenant-configurable prefix / width endpoint** — counter rows
 *   use defaults (`prefix=''`, `width=6`). Admin endpoint to edit
 *   is a later slice.
 */
@Component
class MrnGenerator(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {

    /**
     * Mints the next MRN for [tenantId] and returns the formatted
     * string. MUST be called inside an active transaction.
     *
     * @throws IllegalStateException if no transaction is active
     * @throws MrnGenerationException if the upsert returns no row
     *   (possible causes: missing RLS GUC, insufficient role)
     */
    fun generate(tenantId: UUID): String {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "MrnGenerator.generate() MUST be called inside an active transaction so " +
                "the counter bump rolls back atomically with the patient INSERT. " +
                "Reached without an active tx — check the caller's WriteGate wiring."
        }
        val now = Instant.now(clock)
        val state = jdbcTemplate.queryForObject(
            UPSERT_SQL,
            { rs, _ ->
                CounterState(
                    nextValue = rs.getLong("next_value"),
                    prefix = rs.getString("prefix"),
                    width = rs.getInt("width"),
                    formatKind = MrnFormatKind.valueOf(rs.getString("format_kind")),
                )
            },
            tenantId,
            /* prefix     */ DEFAULT_PREFIX,
            /* width      */ DEFAULT_WIDTH,
            /* formatKind */ DEFAULT_FORMAT_KIND.name,
            /* next_value */ INITIAL_NEXT_VALUE,
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now),
        ) ?: throw MrnGenerationException(
            "MrnGenerator: upsert on clinical.patient_mrn_counter returned no row for " +
                "tenant_id=$tenantId. Likely causes: RLS GUC missing, caller lacks " +
                "OWNER/ADMIN role, tenant does not exist.",
        )
        return format(state)
    }

    private fun format(state: CounterState): String =
        when (state.formatKind) {
            MrnFormatKind.NUMERIC -> {
                val numeric = state.nextValue.toString()
                val padded = if (state.width > 0) numeric.padStart(state.width, '0') else numeric
                "${state.prefix}$padded"
            }
        }

    private data class CounterState(
        val nextValue: Long,
        val prefix: String,
        val width: Int,
        val formatKind: MrnFormatKind,
    )

    private companion object {
        const val DEFAULT_PREFIX: String = ""
        const val DEFAULT_WIDTH: Int = 6
        val DEFAULT_FORMAT_KIND: MrnFormatKind = MrnFormatKind.NUMERIC

        // 1 because next_value is monotonic and starts at 1 for fresh tenants.
        // The ON CONFLICT path bumps by 1; the INSERT path plants 1 directly
        // (meaning the first mint for a tenant returns 1).
        const val INITIAL_NEXT_VALUE: Long = 1L

        // Parameter order (? = positional binding):
        //   1. tenant_id
        //   2. prefix
        //   3. width
        //   4. format_kind
        //   5. next_value (INITIAL, only for the INSERT branch)
        //   6. created_at (INSERT branch)
        //   7. updated_at (INSERT branch)
        //   8. updated_at (UPDATE branch — same value)
        //
        // The RETURNING clause reads post-update values.
        private const val UPSERT_SQL: String = """
            INSERT INTO clinical.patient_mrn_counter (
                tenant_id, prefix, width, format_kind, next_value,
                created_at, updated_at, row_version
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT (tenant_id) DO UPDATE
              SET next_value  = clinical.patient_mrn_counter.next_value + 1,
                  updated_at  = ?,
                  row_version = clinical.patient_mrn_counter.row_version + 1
            RETURNING next_value, prefix, width, format_kind
        """
    }
}

/**
 * Thrown when [MrnGenerator.generate] cannot mint a value.
 *
 * Wrapped by Phase 3G's `GlobalExceptionHandler.onUncaught`
 * fallback to 500 `server.error`; the caller never sees the
 * generator's internal detail. Structured logs correlate via
 * `requestId`.
 */
class MrnGenerationException(message: String) : RuntimeException(message)
