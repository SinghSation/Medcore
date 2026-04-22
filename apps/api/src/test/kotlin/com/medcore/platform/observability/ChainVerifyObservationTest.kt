package com.medcore.platform.observability

import com.medcore.TestcontainersConfiguration
import com.medcore.platform.audit.chain.ChainVerifier
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Asserts `@Observed` on `ChainVerifier.verify` produces the
 * expected timer metric with the closed-set `medcore.audit.chain.outcome`
 * tag (Phase 3F.2).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ChainVerifyObservationTest {

    @Autowired
    lateinit var verifier: ChainVerifier

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Test
    fun `chain verify emits medcore_audit_chain_verify timer on clean chain`() {
        val baseline = timerCount()

        verifier.verify()

        val post = timerCount()
        assertThat(post - baseline)
            .describedAs("medcore.audit.chain.verify timer must record a sample per verify() call")
            .isGreaterThanOrEqualTo(1L)
    }

    @Test
    fun `chain verify timer tags are allow-list subset only`() {
        verifier.verify()

        val timers = meterRegistry.find("medcore.audit.chain.verify").timers()
        assertThat(timers)
            .describedAs("medcore.audit.chain.verify timer must be registered")
            .isNotEmpty()

        timers.forEach { timer ->
            val keys = timer.id.tags.map { it.key }.toSet()
            assertThat(keys).allSatisfy { key ->
                assertThat(
                    key == "medcore.audit.chain.outcome" ||
                        key == "class" ||
                        key == "method" ||
                        key == "error" ||
                        key.startsWith("exception.") ||
                        key.startsWith("otel.") ||
                        key.startsWith("code.")
                ).isTrue()
            }
        }

        val outcomeTags = timers.flatMap { it.id.tags }
            .filter { it.key == "medcore.audit.chain.outcome" }
            .map { it.value }
            .toSet()
        assertThat(outcomeTags)
            .describedAs("outcome values must be the closed set {clean, broken, verifier_failed}")
            .allSatisfy { value ->
                assertThat(value).isIn("clean", "broken", "verifier_failed")
            }
    }

    private fun timerCount(): Long =
        meterRegistry.find("medcore.audit.chain.verify").timers().sumOf { it.count() }
}
