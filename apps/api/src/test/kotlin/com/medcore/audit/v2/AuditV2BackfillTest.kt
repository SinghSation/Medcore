package com.medcore.audit.v2

import com.medcore.TestcontainersConfiguration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Proves the V9 backfill path works against a v1-shaped row set.
 *
 * V9 runs `audit.rebuild_chain()` once during migration, then
 * enforces NOT NULL on `sequence_no` and `row_hash`. Against a fresh
 * Testcontainers database the table is empty at V9 time, so the
 * migration-time backfill is a no-op. This test simulates a
 * production rollout from 3C → 3D by:
 *
 *   1. Clearing `sequence_no`, `prev_hash`, and `row_hash` on
 *      existing rows (DROP NOT NULL first — requires superuser).
 *   2. Calling `audit.rebuild_chain()` to re-derive the chain.
 *   3. Calling `audit.verify_chain()` and asserting zero breaks.
 *   4. Re-asserting NOT NULL so subsequent tests see the v2
 *      invariants intact.
 *
 * This keeps the backfill logic exercised in CI even though it does
 * not run at migration time on a fresh DB.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AuditV2BackfillTest {

    @Autowired
    lateinit var dataSource: DataSource

    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun reset() {
        jdbc = JdbcTemplate(dataSource)
        jdbc.update("DELETE FROM audit.audit_event")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `rebuild_chain produces a valid chain over existing v1-shaped rows`() {
        // 1. Seed 3 "v1-shaped" rows by dropping NOT NULL on the chain
        //    columns, inserting, and re-enforcing NOT NULL at the end.
        jdbc.update("ALTER TABLE audit.audit_event ALTER COLUMN sequence_no DROP NOT NULL")
        jdbc.update("ALTER TABLE audit.audit_event ALTER COLUMN row_hash DROP NOT NULL")

        val recordedTimes = listOf(
            Instant.parse("2026-04-01T10:00:00.000000Z"),
            Instant.parse("2026-04-01T10:00:01.000000Z"),
            Instant.parse("2026-04-01T10:00:02.000000Z"),
        )
        val ids = recordedTimes.map { ts ->
            val id = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO audit.audit_event (
                    id, recorded_at, actor_type, action, outcome
                ) VALUES (?, ?, 'SYSTEM', ?, 'SUCCESS')
                """.trimIndent(),
                id,
                java.sql.Timestamp.from(ts),
                "identity.user.provisioned",
            )
            id
        }

        // 2. Run the backfill
        val seeded = jdbc.queryForObject(
            "SELECT audit.rebuild_chain()",
            Long::class.java,
        )
        assertEquals(3L, seeded)

        // 3. Verify every row now has the v2 columns populated
        val rows = jdbc.query(
            """
            SELECT id, sequence_no, prev_hash, row_hash
              FROM audit.audit_event
             ORDER BY sequence_no
            """.trimIndent(),
            { rs, _ ->
                Triple(
                    rs.getObject("id", UUID::class.java),
                    rs.getLong("sequence_no"),
                    Pair(rs.getBytes("prev_hash"), rs.getBytes("row_hash")),
                )
            },
        )
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.second })
        assertEquals(ids, rows.map { it.first }, "chain order must match recorded_at order")
        assertNull(rows[0].third.first, "first row's prev_hash must be NULL")
        assertNotNull(rows[0].third.second, "first row's row_hash must be set")
        assertEquals(
            rows[0].third.second.toList(),
            rows[1].third.first!!.toList(),
            "sequence_no 2 prev_hash must equal sequence_no 1 row_hash",
        )
        assertEquals(
            rows[1].third.second.toList(),
            rows[2].third.first!!.toList(),
            "sequence_no 3 prev_hash must equal sequence_no 2 row_hash",
        )

        // 4. verify_chain returns no breaks
        val breaks = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit.verify_chain()",
            Int::class.java,
        )
        assertEquals(0, breaks, "a freshly rebuilt chain must verify clean")

        // 5. Restore NOT NULL so subsequent tests see v2 invariants intact.
        jdbc.update("ALTER TABLE audit.audit_event ALTER COLUMN sequence_no SET NOT NULL")
        jdbc.update("ALTER TABLE audit.audit_event ALTER COLUMN row_hash SET NOT NULL")
    }
}
