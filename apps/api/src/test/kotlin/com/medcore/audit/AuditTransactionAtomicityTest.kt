package com.medcore.audit

import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import com.medcore.platform.audit.AuditEventCommand
import com.medcore.platform.audit.AuditWriter
import java.util.UUID
import javax.sql.DataSource
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Proves the load-bearing ADR-003 §2 invariant for Audit v1: when the
 * audit write fails, the audited action is rolled back. This is what
 * "synchronous, inside the caller's `@Transactional` scope" means in
 * practice — the INSERT into `audit.audit_event` must share a
 * transaction with any business write that triggered it, and a thrown
 * audit error must take the business write down with it.
 *
 * The test replaces [com.medcore.platform.audit.JdbcAuditWriter] with a
 * [PoisonableAuditWriter] (via a `@Primary` test bean scoped to this
 * class only). When the writer is "poisoned", the next `write(...)` call
 * throws — simulating any reason the real audit insert could fail
 * (DB outage, grant misconfiguration, constraint violation). The test
 * then exercises the `/me` path, which inside a single `@Transactional`
 * scope first inserts an `identity.user` row and then asks the audit
 * writer to record `identity.user.provisioned`.
 *
 * Expected outcome: the response is NOT 200, and `identity.user` has
 * NO row for the test subject. The rollback proves transactional
 * atomicity end-to-end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(
    TestcontainersConfiguration::class,
    AuditTransactionAtomicityTest.PoisonableAuditWriterConfiguration::class,
)
class AuditTransactionAtomicityTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var mockOAuth2Server: MockOAuth2Server

    @Autowired
    @Qualifier("adminDataSource")
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var poisonable: PoisonableAuditWriter

    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun reset() {
        poisonable.clear()
        jdbc = JdbcTemplate(dataSource)
        jdbc.update("DELETE FROM audit.audit_event")
        jdbc.update("DELETE FROM identity.\"user\"")
    }

    @Test
    fun `audit write failure rolls back identity user insert in the same transaction`() {
        poisonable.poisonNextWrite(
            IllegalStateException("simulated audit failure for atomicity test"),
        )

        val subject = "atomicity-${UUID.randomUUID()}"
        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(
                HttpHeaders().apply {
                    set(HttpHeaders.AUTHORIZATION, "Bearer ${tokenFor(subject)}")
                },
            ),
            String::class.java,
        )

        assertNotEquals(
            HttpStatus.OK,
            response.statusCode,
            "simulated audit failure must NOT be silently swallowed (Rule 06)",
        )

        val identityCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM identity.\"user\" WHERE subject = ?",
            Int::class.java,
            subject,
        )
        assertEquals(
            0,
            identityCount,
            "identity.user insert MUST be rolled back when the audit write fails (ADR-003 §2)",
        )

        val auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit.audit_event",
            Int::class.java,
        )
        assertEquals(
            0,
            auditCount,
            "no audit row should exist — the writer threw, so nothing committed",
        )
    }

    private fun tokenFor(subject: String): String =
        mockOAuth2Server.issueToken(
            issuerId = MOCK_ISSUER_ID,
            clientId = "medcore-test-client",
            tokenCallback = DefaultOAuth2TokenCallback(
                issuerId = MOCK_ISSUER_ID,
                subject = subject,
                claims = mapOf(
                    "email" to "$subject@medcore.test",
                    "email_verified" to true,
                ),
            ),
        ).serialize()

    /**
     * Test-only [AuditWriter]. By default it is a no-op (the test never
     * asserts on real audit row content — that is covered by the other
     * audit suites). When [poisonNextWrite] is called, the next
     * `write(...)` throws the supplied exception once and reverts to
     * no-op. This is enough to exercise the rollback path without
     * needing a mock framework.
     */
    class PoisonableAuditWriter : AuditWriter {

        @Volatile
        private var poison: Throwable? = null

        fun poisonNextWrite(t: Throwable) {
            poison = t
        }

        fun clear() {
            poison = null
        }

        override fun write(command: AuditEventCommand) {
            val current = poison
            if (current != null) {
                poison = null
                throw current
            }
            // Otherwise: deliberate no-op. Real audit-write coverage lives
            // in AuditIdentityIntegrationTest / AuditTenancyIntegrationTest.
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class PoisonableAuditWriterConfiguration {
        @Bean
        @Primary
        fun poisonableAuditWriter(): PoisonableAuditWriter = PoisonableAuditWriter()
    }
}
