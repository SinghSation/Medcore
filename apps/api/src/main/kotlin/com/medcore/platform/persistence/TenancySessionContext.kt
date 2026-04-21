package com.medcore.platform.persistence

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Sets the per-request session GUCs that RLS policies read:
 *   - `app.current_user_id`
 *   - `app.current_tenant_id`
 *
 * Semantics (ADR-001 §2 RLS gate):
 *
 *   - Values are set via `set_config(name, value, is_local := true)`,
 *     which is equivalent to `SET LOCAL`. The new value lives only for
 *     the duration of the current transaction; when the transaction
 *     commits or rolls back, Postgres reverts the setting for the
 *     connection. This is how we avoid GUC leakage across pooled
 *     connections — there is no explicit "clear" path needed, and
 *     there is no window in which a pooled connection can carry a
 *     previous caller's identity into a new request.
 *
 *   - Missing / empty settings deliberately read as NULL in policies
 *     (`NULLIF(current_setting(..., true), '')::uuid`). Every RLS
 *     policy keyed on these GUCs is fail-closed: a comparison against
 *     NULL yields UNKNOWN, which filters the row out.
 *
 *   - MUST be called inside an active Spring transaction. Calling
 *     outside a transaction would either apply the setting globally
 *     (depending on autocommit) or emit an error on rollback-only
 *     connections. The precondition check makes the failure loud.
 *
 * Canonical injection point: the top of every `@Transactional`
 * method in `com.medcore.tenancy.service.TenancyService` — i.e., the
 * boundary that crosses from un-GUC'd Spring-managed transactions
 * into SQL that queries tenant-scoped tables. Identity and audit
 * services do not call this (they do not query tenant-scoped tables).
 */
@Component
class TenancySessionContext(
    private val jdbcTemplate: JdbcTemplate,
) {

    /**
     * Sets both GUCs on the current transaction's connection. Either
     * value may be null; a null value is passed as an empty string so
     * the policy's `NULLIF(..., '')::uuid` reads it as NULL and the
     * row filter fails closed.
     */
    fun apply(userId: UUID?, tenantId: UUID?) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "TenancySessionContext.apply() MUST be called inside an active transaction; " +
                "SET LOCAL scope requires it. Reached without an active Spring tx — check the " +
                "caller's @Transactional."
        }
        setLocal(KEY_USER_ID, userId?.toString().orEmpty())
        setLocal(KEY_TENANT_ID, tenantId?.toString().orEmpty())
    }

    private fun setLocal(key: String, value: String) {
        // `SELECT set_config(?, ?, true)` returns the new value as
        // text. We use queryForObject to satisfy the PG JDBC driver's
        // strict ResultSet expectation for SELECT statements and
        // discard the returned string.
        jdbcTemplate.queryForObject(
            SET_CONFIG_SQL,
            String::class.java,
            key,
            value,
        )
    }

    companion object {
        const val KEY_USER_ID: String = "app.current_user_id"
        const val KEY_TENANT_ID: String = "app.current_tenant_id"
        private const val SET_CONFIG_SQL: String = "SELECT set_config(?, ?, true)"
    }
}
