package com.medcore.platform.audit.chain

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Typed configuration for the audit-chain verification scheduled job
 * (Phase 3F.4).
 *
 * Bound from `medcore.audit.chain-verification.*` in application.yaml.
 * Defaults are production-safe: the job is enabled, runs hourly, and
 * the clean-case log is DEBUG (silent at the default INFO root
 * threshold). Dev environments override [verboseCleanLog] to true to
 * see the successful verification log every hour.
 *
 * Field-by-field contract:
 *
 *   - [enabled]            — master switch; `false` disables the
 *                            scheduler entirely (no runs, no audits,
 *                            no logs). Tests default to false in
 *                            application-test.yaml overrides.
 *   - [cron]               — Spring `@Scheduled`-compatible cron
 *                            expression evaluated in UTC. Default:
 *                            top of every hour. First execution
 *                            fires at the next matching cron time
 *                            after application start — Spring's
 *                            `@Scheduled` does NOT support a separate
 *                            initial-delay for cron triggers. Dev
 *                            shortens the cron (e.g., every 30s) via
 *                            env var for faster feedback.
 *   - [verboseCleanLog]    — when `true`, clean-case verification
 *                            logs at INFO (visible under default
 *                            logging config). When `false`
 *                            (production default), clean-case logs
 *                            at DEBUG (silent under default config).
 *                            Broken-chain and verifier-failed logs
 *                            always fire at ERROR regardless.
 *
 * Concurrency guard (single-instance) is implemented in
 * [ChainVerificationScheduler]; it is not configurable here. When
 * Medcore runs multi-instance, ShedLock (or equivalent distributed
 * lock) lands with its own ADR.
 */
@ConfigurationProperties(prefix = "medcore.audit.chain-verification")
@Validated
data class ChainVerificationProperties(
    val enabled: Boolean = true,
    @field:NotBlank
    val cron: String = "0 0 * * * *",
    val verboseCleanLog: Boolean = false,
)
