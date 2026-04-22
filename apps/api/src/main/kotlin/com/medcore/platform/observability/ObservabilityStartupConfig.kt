package com.medcore.platform.observability

import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Operational visibility for OpenTelemetry export configuration
 * (Phase 3F.2).
 *
 * The export endpoints default to unset (`management.otlp.tracing.endpoint`
 * and `management.otlp.metrics.export.url` are blank in the baseline
 * `application.yaml`). That posture is production-safe — nothing phones
 * home unless an operator explicitly points at a collector — but it
 * is also silent, which creates a false-sense-of-observability risk.
 * An engineer investigating a production incident could assume traces
 * exist and waste the debugging window before discovering they do not.
 *
 * This file addresses that risk with two complementary signals:
 *
 * 1. **Startup log line.** [ObservabilityStartupReporter] fires on
 *    `ApplicationReadyEvent` and logs at INFO exactly one line for
 *    the trace-export state and one for the metric-export state.
 *    Visible in container logs; captured by any log aggregator.
 *
 * 2. **`/actuator/info` contributor.** [TelemetryExportInfoContributor]
 *    adds a `telemetry` block to the `/actuator/info` response with
 *    boolean `traces.enabled` and `metrics.enabled` plus the
 *    configured endpoint URL (or "disabled" when unset). Operators
 *    can query this from a script or a dashboard without scraping
 *    logs.
 *
 * Neither signal affects `/actuator/health` — OTLP being disabled is
 * a valid operational state, not a health failure. Reporting it as
 * DOWN would break probes unnecessarily.
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityStartupConfig

@Component
class ObservabilityStartupReporter(
    @Value("\${management.otlp.tracing.endpoint:}") private val tracingEndpoint: String,
    @Value("\${management.otlp.metrics.export.url:}") private val metricsEndpoint: String,
    @Value("\${management.tracing.sampling.probability:0.1}") private val samplingProbability: Double,
) {

    private val log = LoggerFactory.getLogger(ObservabilityStartupReporter::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun reportAtStartup() {
        val tracingStatus = if (tracingEndpoint.isBlank()) DISABLED_LITERAL else tracingEndpoint
        val metricsStatus = if (metricsEndpoint.isBlank()) DISABLED_LITERAL else metricsEndpoint

        log.info(
            "[OBSERVABILITY] OpenTelemetry trace export: {} (sampling probability={})",
            tracingStatus,
            samplingProbability,
        )
        log.info("[OBSERVABILITY] OpenTelemetry metric export: {}", metricsStatus)

        if (tracingEndpoint.isBlank() && metricsEndpoint.isBlank()) {
            log.warn(
                "[OBSERVABILITY] OpenTelemetry export is fully disabled — " +
                    "spans and metrics are collected in-process but never shipped. " +
                    "Configure MEDCORE_OTLP_TRACING_ENDPOINT / MEDCORE_OTLP_METRICS_URL " +
                    "to enable export."
            )
        }
    }

    companion object {
        const val DISABLED_LITERAL: String = "disabled (no OTLP endpoint configured)"
    }
}

@Component
class TelemetryExportInfoContributor(
    @Value("\${management.otlp.tracing.endpoint:}") private val tracingEndpoint: String,
    @Value("\${management.otlp.metrics.export.url:}") private val metricsEndpoint: String,
    @Value("\${management.tracing.sampling.probability:0.1}") private val samplingProbability: Double,
) : InfoContributor {

    override fun contribute(builder: Info.Builder) {
        builder.withDetail(
            "telemetry",
            mapOf(
                "traces" to mapOf(
                    "enabled" to tracingEndpoint.isNotBlank(),
                    "endpoint" to endpointOrDisabled(tracingEndpoint),
                    "samplingProbability" to samplingProbability,
                ),
                "metrics" to mapOf(
                    "enabled" to metricsEndpoint.isNotBlank(),
                    "endpoint" to endpointOrDisabled(metricsEndpoint),
                ),
            ),
        )
    }

    private fun endpointOrDisabled(endpoint: String): String =
        if (endpoint.isBlank()) "disabled" else endpoint
}
