package com.medcore.platform.observability

import io.micrometer.common.KeyValue
import io.micrometer.common.KeyValues
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Belt-on-braces guard that strips sensitive observation attributes
 * regardless of where in the instrumentation stack they originated.
 *
 * **Two-mode filter:**
 *
 * 1. **Auto-instrumented observations (HTTP, JDBC, etc.)** — deny
 *    list. Keys matching any of [AUTO_INSTRUMENTATION_DENY_PATTERNS]
 *    are removed. Catches a future auto-instrumentation default
 *    flip (e.g., an OpenTelemetry release that starts capturing
 *    HTTP headers) without Medcore code or config changes.
 *
 * 2. **Medcore-custom observations (any observation whose name
 *    starts with `medcore.`)** — allow list. Only attributes whose
 *    keys are in [MEDCORE_CUSTOM_ALLOW_PATTERNS] OR begin with one
 *    of the standard OTel prefixes ([STANDARD_OTEL_PREFIXES]) are
 *    retained. Every other attribute is stripped.
 *
 * The allow-list discipline on custom spans is the PHI-safety
 * gate: a future slice that adds a `medcore.audit.actor_id` tag
 * would be silently stripped until that key is added to the allow
 * list via a deliberate code change (and a review-pack callout).
 *
 * This filter runs on every observation regardless of exporter
 * configuration, so OTLP-disabled and OTLP-enabled environments
 * behave identically with respect to attribute content.
 */
@Configuration(proxyBeanMethods = false)
class ObservationAttributeFilterConfig {

    @Bean
    fun sensitiveAttributeFilter(): ObservationFilter = ObservationFilter { context ->
        val filtered: KeyValues = if (isMedcoreCustom(context)) {
            allowListCustom(context.lowCardinalityKeyValues)
        } else {
            denyListAuto(context.lowCardinalityKeyValues)
        }
        context.removeLowCardinalityKeyValues(*context.lowCardinalityKeyValues.stream()
            .map { it.key }.toList().toTypedArray())
        filtered.forEach { kv -> context.addLowCardinalityKeyValue(kv) }

        val filteredHigh: KeyValues = if (isMedcoreCustom(context)) {
            allowListCustom(context.highCardinalityKeyValues)
        } else {
            denyListAuto(context.highCardinalityKeyValues)
        }
        context.removeHighCardinalityKeyValues(*context.highCardinalityKeyValues.stream()
            .map { it.key }.toList().toTypedArray())
        filteredHigh.forEach { kv -> context.addHighCardinalityKeyValue(kv) }

        context
    }

    private fun isMedcoreCustom(context: Observation.Context): Boolean =
        context.name?.startsWith("medcore.") == true

    private fun allowListCustom(input: KeyValues): KeyValues =
        KeyValues.of(
            input.stream()
                .filter { kv -> isAllowedCustomKey(kv.key) }
                .toList()
        )

    private fun denyListAuto(input: KeyValues): KeyValues =
        KeyValues.of(
            input.stream()
                .filter { kv -> !isDeniedAutoKey(kv.key) }
                .toList()
        )

    private fun isAllowedCustomKey(key: String): Boolean {
        if (MEDCORE_CUSTOM_ALLOW_PATTERNS.any { it == key }) return true
        if (STANDARD_OTEL_PREFIXES.any { key.startsWith(it) }) return true
        return false
    }

    private fun isDeniedAutoKey(key: String): Boolean =
        AUTO_INSTRUMENTATION_DENY_PATTERNS.any { pattern -> matches(key, pattern) }

    private fun matches(key: String, pattern: String): Boolean =
        if (pattern.endsWith(".*")) {
            key.startsWith(pattern.dropLast(1))
        } else {
            key == pattern
        }

    companion object {
        /**
         * Exact or `prefix.*`-form keys stripped from
         * auto-instrumented observations. Defence-in-depth — these
         * are already absent by default in Spring Boot 3.4, but
         * future default flips would re-introduce them.
         */
        val AUTO_INSTRUMENTATION_DENY_PATTERNS: List<String> = listOf(
            // HTTP header capture (Authorization especially)
            "http.request.header.*",
            "http.response.header.*",
            "header.*",
            // URL query parameters
            "http.request.query.*",
            "url.query",
            "query.*",
            // Prepared statement parameter values (potential PHI)
            "sql.parameters",
            "db.statement.parameters",
            "db.sql.parameters",
            // Request / response bodies
            "http.request.body",
            "http.response.body",
            // Future-proof patient-shaped fields
            "patient.*",
            "user.email",
            "user.name",
            "user.display_name",
        )

        /**
         * Closed set of keys permitted on Medcore-custom observation
         * attributes. Adding a new key requires a deliberate edit
         * AND a review-pack callout AND a PHI-exposure review
         * amendment AND a test update in
         * `TracingPhiLeakageTest.custom audit span attribute set is
         * the expected allow list`.
         */
        val MEDCORE_CUSTOM_ALLOW_PATTERNS: List<String> = listOf(
            "medcore.audit.action",
            "medcore.audit.outcome",
            "medcore.audit.chain.outcome",
        )

        /**
         * Standard OpenTelemetry attribute prefixes that are always
         * permitted even on Medcore-custom spans (these carry
         * framework-level metadata like span status or method
         * name — not PHI).
         */
        val STANDARD_OTEL_PREFIXES: List<String> = listOf(
            "otel.",
            "error",
            "exception.",
            "code.",
            // Micrometer's built-in class/method tags from @Observed
            "class",
            "method",
        )
    }
}
