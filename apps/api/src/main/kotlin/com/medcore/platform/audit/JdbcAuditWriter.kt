package com.medcore.platform.audit

import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.annotation.Observed
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * JDBC-based append-only writer for `audit.audit_event` (Audit v2 per
 * ADR-003 §2, Phase 3D).
 *
 * v2 changes vs v1:
 *   - Writes now go through the DB function `audit.append_event(...)`,
 *     which acquires `pg_advisory_xact_lock`, derives the next
 *     `sequence_no` and `prev_hash` from the chain tip, computes the
 *     row's `row_hash`, and INSERTs in one atomic step. The Kotlin
 *     side no longer computes hashes — canonicalisation and hashing
 *     live exclusively in Postgres so the writer, the migration
 *     backfill, and `audit.verify_chain()` share a single byte-stable
 *     source of truth.
 *   - Everything in v1 that made the writer safe is preserved:
 *       - `TransactionTemplate` with PROPAGATION_REQUIRED joins the
 *         caller's transaction (identity / tenancy services) or
 *         starts a fresh one (filter / entry point). The
 *         `AuditTransactionAtomicityTest` continues to prove rollback
 *         semantics end-to-end.
 *       - Exceptions propagate (ADR-003 §2 — failure to write audit
 *         fails the audited action).
 *       - Request metadata is lifted by [RequestMetadataProvider]; no
 *         caller touches HTTP internals.
 *       - No free-form payload bag. [AuditEventCommand] stays flat
 *         and typed.
 *
 * Role / grants: the writer runs SQL as the application's runtime
 * datasource role. V9 grants `EXECUTE ON audit.append_event(...)` to
 * `medcore_app`; superusers (the current local-dev role) have the
 * permission implicitly. When the runtime role switch lands (deferred
 * ops slice), no writer change is required.
 */
@Service
class JdbcAuditWriter(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val requestMetadataProvider: RequestMetadataProvider,
    private val clock: Clock,
    private val observationRegistry: ObservationRegistry,
) : AuditWriter {

    private val transactionTemplate: TransactionTemplate = TransactionTemplate(transactionManager)

    /**
     * Writes an audit row through `audit.append_event(...)`.
     *
     * **Observation surface (Phase 3F.2).** This method is wrapped in a
     * Medcore-custom observation named `medcore.audit.write` that
     * produces both a timer metric (latency histogram tagged by
     * action + outcome) and a span visible in OpenTelemetry trace
     * backends. The only attributes added are:
     *
     *   - `medcore.audit.action`  — the action enum's closed-set
     *                                wire code (e.g. `identity.user.login.success`)
     *   - `medcore.audit.outcome` — SUCCESS / DENIED / ERROR
     *
     * **⚠️ PHI GUARDRAIL — READ BEFORE MODIFYING.** Under no
     * circumstance may future code add any of the following as span
     * attributes, tags, or baggage on this observation:
     *
     *   - Actor IDs (`actor_id`), tenant IDs (`tenant_id`), resource
     *     IDs (`resource_id`).
     *   - Actor display name, email, preferred username.
     *   - The `reason` string's content.
     *   - Any column value of the audit row being appended.
     *   - Any representation of the `AuditEventCommand` payload.
     *
     * Those fields live in the `audit.audit_event` table (access-
     * controlled via grants) and in MDC (request-scoped, cleared
     * on request exit). They do NOT belong in span attributes
     * because span backends often have weaker access controls than
     * the audit DB, and spans may be sampled-out — creating a
     * disclosure path that varies by sampling rate.
     *
     * The allow-list in
     * [com.medcore.platform.observability.ObservationAttributeFilterConfig]
     * enforces this by stripping any `medcore.*` attribute not on
     * `MEDCORE_CUSTOM_ALLOW_PATTERNS`. This KDoc is the developer-
     * facing contract; the filter is the runtime backstop.
     * `TracingPhiLeakageTest` asserts both.
     */
    @Observed(
        name = "medcore.audit.write",
        contextualName = "audit.write",
    )
    override fun write(command: AuditEventCommand) {
        val metadata = requestMetadataProvider.current()
        val recordedAt = Instant.now(clock)
        val id = UUID.randomUUID()

        // Add the minimal, PHI-safe attributes to the current
        // observation (created by @Observed). `Observation.tryScoped`
        // returns the active observation if any — via the
        // ObservationRegistry — so we do not open a second scope.
        val currentObservation = observationRegistry.currentObservation
        if (currentObservation != null) {
            currentObservation.lowCardinalityKeyValue(
                "medcore.audit.action",
                command.action.code,
            )
            currentObservation.lowCardinalityKeyValue(
                "medcore.audit.outcome",
                command.outcome.name,
            )
        }

        transactionTemplate.executeWithoutResult {
            // PG JDBC's executeUpdate() rejects SELECT; the function
            // call returns a BIGINT sequence_no we discard. Using
            // execute() lets the driver handle the ResultSet path.
            jdbcTemplate.execute(APPEND_EVENT_SQL) { ps: PreparedStatement ->
                var i = 1
                ps.setObject(i++, id)
                ps.setTimestamp(i++, Timestamp.from(recordedAt))
                setNullableUuid(ps, i++, command.tenantId)
                ps.setString(i++, command.actorType.name)
                setNullableUuid(ps, i++, command.actorId)
                ps.setString(i++, command.actorDisplay)
                ps.setString(i++, command.action.code)
                ps.setString(i++, command.resourceType)
                ps.setString(i++, command.resourceId)
                ps.setString(i++, command.outcome.name)
                ps.setString(i++, metadata.requestId)
                ps.setString(i++, metadata.clientIp)
                ps.setString(i++, metadata.userAgent)
                ps.setString(i, command.reason)
                ps.execute()
                null
            }
        }
    }

    private fun setNullableUuid(ps: PreparedStatement, index: Int, value: UUID?) {
        if (value == null) {
            ps.setNull(index, Types.OTHER)
        } else {
            ps.setObject(index, value)
        }
    }

    private companion object {
        // Positional parameters matching audit.append_event's signature
        // in V9. `::inet` cast handles null strings cleanly
        // (null-cast-to-inet = null INET).
        const val APPEND_EVENT_SQL: String = """
            SELECT audit.append_event(
                ?::uuid, ?, ?::uuid,
                ?, ?::uuid, ?,
                ?, ?, ?, ?,
                ?, ?::inet, ?, ?
            )
        """
    }
}
