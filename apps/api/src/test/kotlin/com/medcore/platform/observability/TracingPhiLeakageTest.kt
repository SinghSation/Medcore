package com.medcore.platform.observability

import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod

/**
 * Operational control: span / metric attributes emitted by
 * `medcore.*`-named observations must be a subset of the closed
 * allow list declared in [ObservationAttributeFilterConfig]
 * (Phase 3F.2, §PHI-safety).
 *
 * This test drives real audit writes and chain verifications (which
 * produce the only Medcore-custom observations in the current
 * slice) and inspects the emitted `MeterRegistry` timers. Any tag
 * key outside the allow list would be an instrumentation leak and
 * MUST be stripped by the filter.
 *
 * Future slices that add new `medcore.*` observations MUST extend
 * this test's allow list AND the filter's
 * `MEDCORE_CUSTOM_ALLOW_PATTERNS` together.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class TracingPhiLeakageTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var mockOAuth2Server: MockOAuth2Server

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Test
    fun `no medcore custom metric carries keys outside the allow list`() {
        // Drive at least one audit write + one chain verify so
        // both custom timer families are populated.
        driveAuditWrite()

        val medcoreTimers = meterRegistry.meters.filter { it.id.name.startsWith("medcore.") }
        assertThat(medcoreTimers)
            .describedAs("at least the medcore.audit.write timer must exist after driving a request")
            .isNotEmpty()

        medcoreTimers.forEach { meter ->
            val keys = meter.id.tags.map { it.key }.toSet()
            keys.forEach { key ->
                assertThat(isAllowedKey(key))
                    .describedAs(
                        "tag key `$key` on meter `${meter.id.name}` is outside the " +
                            "Phase 3F.2 PHI-safe allow list. If the key is legitimately " +
                            "needed, update both " +
                            "ObservationAttributeFilterConfig.MEDCORE_CUSTOM_ALLOW_PATTERNS " +
                            "AND this test's allow list AND the Phase 3F.2 PHI-exposure " +
                            "review, in the same slice."
                    )
                    .isTrue()
            }
        }
    }

    @Test
    fun `no medcore tag value contains obvious PHI shapes`() {
        driveAuditWrite()

        val medcoreTimers = meterRegistry.meters.filter { it.id.name.startsWith("medcore.") }

        medcoreTimers.forEach { meter ->
            meter.id.tags.forEach { tag ->
                val value = tag.value
                assertThat(value)
                    .describedAs("tag value on ${meter.id.name}.${tag.key}")
                    .doesNotContain("@")          // emails
                    .doesNotMatch(".*\\d{3}-\\d{2}-\\d{4}.*") // SSN
                    .doesNotContain("Bearer ")    // bearer tokens
                    .doesNotMatch(".*\\d{4}-\\d{2}-\\d{2}.*") // dates (DOB risk)
                    // UUIDs: catches patient IDs / encounter IDs /
                    // any opaque internal identifier that becomes
                    // sensitive once joined to PHI. Present tag
                    // values (enum slugs, class names) are not
                    // UUID-shaped; any future tag that accidentally
                    // embeds an ID will fail this assertion and
                    // MUST be re-scoped before landing.
                    .doesNotMatch(
                        ".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*",
                    )
            }
        }
    }

    private fun driveAuditWrite() {
        val subject = "phi-${UUID.randomUUID()}"
        val headers = HttpHeaders().apply {
            setBearerAuth(
                mockOAuth2Server.issueToken(
                    issuerId = MOCK_ISSUER_ID,
                    clientId = "medcore-test-client",
                    tokenCallback = DefaultOAuth2TokenCallback(
                        issuerId = MOCK_ISSUER_ID,
                        subject = subject,
                        claims = mapOf(
                            "email" to "$subject@leak-test.example",
                            "email_verified" to true,
                        ),
                    ),
                ).serialize()
            )
        }
        rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )
    }

    /**
     * Allow list for tag keys on any `medcore.*` meter. This must
     * stay in lockstep with
     * [ObservationAttributeFilterConfig.MEDCORE_CUSTOM_ALLOW_PATTERNS].
     * A CI-level check enforcing that lockstep belongs to Phase 3I.
     */
    private fun isAllowedKey(key: String): Boolean =
        key == "medcore.audit.action" ||
            key == "medcore.audit.outcome" ||
            key == "medcore.audit.chain.outcome" ||
            key == "class" ||
            key == "method" ||
            key == "error" ||
            key.startsWith("exception.") ||
            key.startsWith("otel.") ||
            key.startsWith("code.")
}
