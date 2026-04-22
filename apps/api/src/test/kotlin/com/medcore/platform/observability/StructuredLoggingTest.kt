package com.medcore.platform.observability

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.medcore.TestcontainersConfiguration
import com.medcore.TestcontainersConfiguration.Companion.MOCK_ISSUER_ID
import java.util.UUID
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * Asserts the **contract** of structured logging without locking the
 * test to a specific vendor layout (per Phase 3F.1 DoD §3.1.1,
 * refinement #4).
 *
 * The contract:
 *   - Captured stdout contains at least one valid JSON log line.
 *   - Every captured JSON log line carries `message`, a level field
 *     (under `log.level` or `level`), and a logger field (under
 *     `log.logger`, `logger`, or `logger_name`) — synonyms accepted
 *     so the test is format-independent.
 *   - When this test explicitly emits a log line with `request_id`
 *     in MDC, that emission appears as a JSON line that carries
 *     the same `request_id` as a top-level (or MDC-nested) field.
 *
 * Intentional non-assertions:
 *   - Exact field names / ordering / timestamp format (ECS,
 *     Logstash, GELF disagree; operator-configurable via
 *     `MEDCORE_LOG_FORMAT`).
 *   - That application code emits a log line per request. Medcore
 *     does not log per request by default; correlation into the
 *     audit chain is proven end-to-end by
 *     [RequestIdAuditCorrelationTest].
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class StructuredLoggingTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var mockOAuth2Server: MockOAuth2Server

    private val mapper = ObjectMapper()
    private val testLogger = LoggerFactory.getLogger(StructuredLoggingTest::class.java)

    @Test
    fun `log output is JSON and carries the required contract fields`(output: CapturedOutput) {
        // Emit a known log line first so the assertion is deterministic
        // regardless of Spring context caching (cached contexts skip
        // bootstrap log output on subsequent test runs). Drive a real
        // HTTP request in addition so the test exercises the code path
        // that the observability substrate instruments.
        testLogger.info("structured-logging-contract-probe")
        val response = rest.exchange(
            "/api/v1/me",
            HttpMethod.GET,
            HttpEntity<Void>(bearer(tokenFor("struct-${UUID.randomUUID()}"))),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val jsonLines = output.all.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { mapper.readTree(line) }.getOrNull() }
            .toList()

        assertThat(jsonLines)
            .describedAs("structured logging must emit JSON log lines")
            .isNotEmpty()

        jsonLines.forEach { node ->
            assertThat(hasMessage(node))
                .describedAs("log line missing message field: $node")
                .isTrue()
            assertThat(hasLevel(node))
                .describedAs("log line missing level field: $node")
                .isTrue()
            assertThat(hasLogger(node))
                .describedAs("log line missing logger field: $node")
                .isTrue()
        }
    }

    @Test
    fun `explicit log emission with MDC request_id round-trips through structured output`(
        output: CapturedOutput,
    ) {
        val marker = "struct-marker-${UUID.randomUUID()}"
        val id = UUID.randomUUID().toString()

        MDC.put(MdcKeys.REQUEST_ID, id)
        try {
            testLogger.info("structured-logging-test-marker {}", marker)
        } finally {
            MDC.remove(MdcKeys.REQUEST_ID)
        }

        val markerLine = output.all.lineSequence()
            .firstOrNull { line -> line.contains(marker) && line.contains("{") }

        assertThat(markerLine)
            .describedAs("marker log line expected in captured output")
            .isNotNull()

        val node = mapper.readTree(markerLine)
        assertThat(node.findValuesAsText(MdcKeys.REQUEST_ID))
            .describedAs("marker log line must carry request_id=$id")
            .contains(id)
    }

    private fun hasMessage(node: JsonNode): Boolean =
        node.hasNonNull("message")

    private fun hasLevel(node: JsonNode): Boolean =
        listOf("log.level", "level").any { name -> node.path(name).isTextual }

    private fun hasLogger(node: JsonNode): Boolean =
        listOf("log.logger", "logger", "logger_name").any { name ->
            node.path(name).isTextual
        }

    private fun tokenFor(subject: String): String =
        mockOAuth2Server.issueToken(
            issuerId = MOCK_ISSUER_ID,
            clientId = "medcore-test-client",
            tokenCallback = DefaultOAuth2TokenCallback(
                issuerId = MOCK_ISSUER_ID,
                subject = subject,
                claims = mapOf("email_verified" to true),
            ),
        ).serialize()

    private fun bearer(token: String) = HttpHeaders().apply {
        set(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}
