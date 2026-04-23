package com.medcore.platform.observability

import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.UUID
import javax.sql.DataSource
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Asserts `@Observed` on `JdbcAuditWriter.write` produces a timer
 * metric and that its tag set is the expected PHI-safe allow list
 * (Phase 3F.2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class AuditWriteObservationTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var mockOAuth2Server: MockOAuth2Server

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Autowired
    @Qualifier("adminDataSource")
    lateinit var adminDataSource: DataSource

    @BeforeEach
    fun reset() {
        JdbcTemplate(adminDataSource).update("DELETE FROM audit.audit_event")
        JdbcTemplate(adminDataSource).update("DELETE FROM clinical.patient_mrn_counter")
        JdbcTemplate(adminDataSource).update("DELETE FROM clinical.patient_identifier")
        JdbcTemplate(adminDataSource).update("DELETE FROM clinical.patient")
        JdbcTemplate(adminDataSource).update("DELETE FROM tenancy.tenant_membership")
        JdbcTemplate(adminDataSource).update("DELETE FROM tenancy.tenant")
        JdbcTemplate(adminDataSource).update("DELETE FROM identity.\"user\"")
        // Reset the timer by creating a fresh registry view per-test is
        // not straightforward; instead this test asserts monotonic
        // increment relative to a baseline count, which is robust to
        // context-reuse across tests.
    }

    @Test
    fun `audit write emits medcore_audit_write timer with allowed tags`() {
        val baseline = timerCount()

        val subject = "observed-${UUID.randomUUID()}"
        val headers = HttpHeaders().apply {
            setBearerAuth(
                mockOAuth2Server.issueToken(
                    issuerId = MOCK_ISSUER_ID,
                    clientId = "medcore-test-client",
                    tokenCallback = DefaultOAuth2TokenCallback(
                        issuerId = MOCK_ISSUER_ID,
                        subject = subject,
                        claims = mapOf("email_verified" to true),
                    ),
                ).serialize()
            )
        }
        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val post = timerCount()
        // First /me produces provisioned + login.success, so at least
        // two write invocations incremented the timer.
        assertThat(post - baseline)
            .describedAs("medcore.audit.write timer must record at least 2 samples for first /me")
            .isGreaterThanOrEqualTo(2L)

        val timers = meterRegistry.find("medcore.audit.write").timers()
        assertThat(timers)
            .describedAs("medcore.audit.write timer must be registered")
            .isNotEmpty()

        timers.forEach { timer ->
            val keys = timer.id.tags.map { it.key }.toSet()
            assertThat(keys)
                .describedAs("tag keys on medcore.audit.write must be a subset of the PHI-safe allow list")
                .allSatisfy { key -> assertThat(isAllowedKey(key)).isTrue() }
        }
    }

    private fun timerCount(): Long =
        meterRegistry.find("medcore.audit.write").timers().sumOf { it.count() }

    /**
     * Allow-list for tags on the `medcore.audit.write` timer. Keys
     * outside this set indicate an instrumentation leak that MUST be
     * stripped by `ObservationAttributeFilterConfig` or explicitly
     * added to the filter's allow list with a review-pack callout.
     */
    private fun isAllowedKey(key: String): Boolean =
        key == "medcore.audit.action" ||
            key == "medcore.audit.outcome" ||
            key == "class" ||
            key == "method" ||
            key == "error" ||
            key.startsWith("exception.") ||
            key.startsWith("otel.") ||
            key.startsWith("code.")
}
