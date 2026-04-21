package com.medcore.platform.audit

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
 * JDBC-based append-only writer for `audit.audit_event` (Audit v1,
 * ADR-003 §2).
 *
 * Why JDBC rather than JPA:
 *   - Audit rows are write-once, read-rarely, never updated. JPA's
 *     change-tracking and dirty-flush machinery is extra rope, not
 *     value, for that shape.
 *   - A single `INSERT` statement against typed positional parameters is
 *     the simplest and easiest-to-review mapping from
 *     [AuditEventCommand] to the V7 schema. The SQL is right here and a
 *     reviewer can verify column-by-column that no free-form content
 *     leaks.
 *
 * Transaction semantics (load-bearing — this is what ADR-003 §2 means
 * by "synchronous, inside the caller's @Transactional scope"):
 *
 *   - Uses a [TransactionTemplate] with `PROPAGATION_REQUIRED` (the
 *     default). When called from a service method that is already
 *     `@Transactional`, Spring's transaction manager has bound a
 *     connection to the current thread; both [JdbcTemplate] and
 *     `TransactionTemplate` go through the same shared
 *     `DataSourceTransactionManager`, so the INSERT joins that
 *     transaction and commits or rolls back atomically with the
 *     audited action.
 *   - When called from outside any transaction (servlet filter,
 *     authentication entry point), `PROPAGATION_REQUIRED` starts a
 *     fresh transaction for the INSERT alone. The audit row commits
 *     independently — there is no audited write to roll back into.
 *   - Exceptions propagate. ADR-003 §2: failure to write audit fails
 *     the audited action. `AuditTransactionAtomicityTest` proves this
 *     by injecting a failure into the writer and asserting the
 *     in-flight `identity.user` insert is rolled back.
 *
 * The writer accepts a typed [AuditEventCommand] and never serializes
 * arbitrary objects. PHI cannot enter through this path without
 * widening [AuditEventCommand] in a separately-reviewed change.
 */
@Service
class JdbcAuditWriter(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val requestMetadataProvider: RequestMetadataProvider,
    private val clock: Clock,
) : AuditWriter {

    private val transactionTemplate: TransactionTemplate = TransactionTemplate(transactionManager)

    override fun write(command: AuditEventCommand) {
        val metadata = requestMetadataProvider.current()
        val recordedAt = Instant.now(clock)
        transactionTemplate.executeWithoutResult {
            jdbcTemplate.update { connection ->
                val sql = """
                    INSERT INTO audit.audit_event (
                        id, recorded_at, tenant_id, actor_type, actor_id, actor_display,
                        action, resource_type, resource_id, outcome,
                        request_id, client_ip, user_agent, reason
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::inet, ?, ?)
                """.trimIndent()
                val ps = connection.prepareStatement(sql)
                ps.setObject(1, UUID.randomUUID())
                ps.setTimestamp(2, Timestamp.from(recordedAt))
                setNullableUuid(ps, 3, command.tenantId)
                ps.setString(4, command.actorType.name)
                setNullableUuid(ps, 5, command.actorId)
                ps.setString(6, command.actorDisplay)
                ps.setString(7, command.action.code)
                ps.setString(8, command.resourceType)
                ps.setString(9, command.resourceId)
                ps.setString(10, command.outcome.name)
                ps.setString(11, metadata.requestId)
                ps.setString(12, metadata.clientIp)
                ps.setString(13, metadata.userAgent)
                ps.setString(14, command.reason)
                ps
            }
        }
    }

    private fun setNullableUuid(
        ps: java.sql.PreparedStatement,
        index: Int,
        value: UUID?,
    ) {
        if (value == null) {
            ps.setNull(index, Types.OTHER)
        } else {
            ps.setObject(index, value)
        }
    }
}
