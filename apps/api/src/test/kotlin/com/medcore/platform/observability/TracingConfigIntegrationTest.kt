package com.medcore.platform.observability

import com.medcore.TestcontainersConfiguration
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.aop.ObservedAspect
import io.micrometer.tracing.Tracer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import

/**
 * Asserts the OpenTelemetry-related beans land cleanly in the Spring
 * context and that sampling / export defaults are in the expected
 * prod-safe posture (Phase 3F.2).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TracingConfigIntegrationTest {

    @Autowired
    lateinit var context: ApplicationContext

    @Autowired
    lateinit var observationRegistry: ObservationRegistry

    @Autowired
    lateinit var tracer: Tracer

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Value("\${management.tracing.sampling.probability}")
    var samplingProbability: Double = -1.0

    @Value("\${management.otlp.tracing.endpoint}")
    lateinit var tracingEndpoint: String

    @Value("\${management.otlp.metrics.export.url}")
    lateinit var metricsEndpoint: String

    @Test
    fun `ObservationRegistry is registered`() {
        assertThat(observationRegistry).isNotNull()
    }

    @Test
    fun `Tracer bridge is registered`() {
        assertThat(tracer).isNotNull()
    }

    @Test
    fun `MeterRegistry is registered`() {
        assertThat(meterRegistry).isNotNull()
    }

    @Test
    fun `ObservedAspect bean is wired for @Observed annotations`() {
        val beans = context.getBeansOfType(ObservedAspect::class.java)
        assertThat(beans).describedAs("ObservedAspect must be registered").isNotEmpty()
    }

    @Test
    fun `sampling probability defaults to prod-safe 0_1`() {
        // application.yaml default is 0.1 (prod-safe). Dev overrides
        // to 1.0 via MEDCORE_TRACING_SAMPLING_PROBABILITY. Tests
        // inherit the default unless a @TestPropertySource overrides.
        assertThat(samplingProbability)
            .describedAs("default sampling probability must be 0.1 in application.yaml")
            .isEqualTo(0.1)
    }

    @Test
    fun `otlp tracing endpoint defaults to unset`() {
        assertThat(tracingEndpoint)
            .describedAs("no export by default — operator opts in via MEDCORE_OTLP_TRACING_ENDPOINT")
            .isEmpty()
    }

    @Test
    fun `otlp metrics endpoint defaults to unset`() {
        assertThat(metricsEndpoint)
            .describedAs("no export by default — operator opts in via MEDCORE_OTLP_METRICS_URL")
            .isEmpty()
    }

    @Test
    fun `no global span leak at rest`() {
        // A stray active span at bean-injection time would indicate a
        // misconfiguration — e.g., a bean initialisation that opens a
        // scope and forgets to close it. The normal steady state
        // outside any request is `currentSpan() == null` (Micrometer
        // convention for "no active scope"). Tripping this assertion
        // is a signal that some future slice has introduced a span
        // leak that would pollute subsequent observations.
        assertThat(tracer.currentSpan())
            .describedAs("tracer must report no active span outside a request scope")
            .isNull()
    }
}
