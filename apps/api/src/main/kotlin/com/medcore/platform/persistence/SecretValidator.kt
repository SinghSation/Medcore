package com.medcore.platform.persistence

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Fail-fast startup check that every required-at-boot secret is
 * present. Supersedes the VERIFY path of
 * [com.medcore.platform.persistence.MedcoreAppPasswordSync] from
 * Phase 3E.
 *
 * Runs in `@PostConstruct` so the check fires during context refresh
 * — BEFORE `ApplicationStartedEvent`, BEFORE `ApplicationReadyEvent`,
 * BEFORE any datasource bean tries to connect. A missing secret
 * causes Spring context refresh itself to fail; the application
 * never reaches readiness, and a liveness probe against the JVM
 * process will see the process exit rather than a "started but
 * unhealthy" state.
 *
 * [REQUIRED_SECRETS] enumerates every secret the application MUST
 * have to boot. Adding a new required secret = add the key here AND
 * update the runbook secrets inventory AND update
 * `SecretValidatorTest.every required secret is probed at boot`.
 * The three-way sync is enforceable by CI in Phase 3I.
 *
 * Non-required secrets (tolerated absence) are NOT validated here —
 * their callers use [SecretSource.getOrNull] at the actual
 * consumption site.
 */
@Component
class SecretValidator(
    private val secretSource: SecretSource,
) {

    private val log = LoggerFactory.getLogger(SecretValidator::class.java)

    @PostConstruct
    fun validate() {
        val missing = mutableListOf<String>()
        REQUIRED_SECRETS.forEach { key ->
            try {
                secretSource.get(key)
            } catch (ex: IllegalStateException) {
                missing += key
            }
        }
        if (missing.isNotEmpty()) {
            val msg = buildString {
                append("REFUSING TO START: ")
                append(missing.size)
                append(" required secret(s) missing: [")
                append(missing.joinToString(", "))
                append("]. See docs/runbooks/secrets-and-migrations.md ")
                append("for the secrets inventory and provisioning procedure.")
            }
            log.error(msg)
            throw IllegalStateException(msg)
        }
        log.info(
            "[SECRETS] SecretValidator: all {} required secrets present via {}",
            REQUIRED_SECRETS.size,
            secretSource::class.simpleName,
        )
    }

    companion object {
        /**
         * Closed list of secrets the application MUST have to boot.
         * Keys are in canonical Spring-property form (lowercase
         * dotted). Spring's relaxed binding resolves each to the
         * corresponding env var at resolve time:
         *
         *   `medcore.db.app.password` ← `MEDCORE_DB_APP_PASSWORD`
         *
         * Adding a new entry requires concurrent updates to:
         *   1. This list.
         *   2. `SecretValidatorTest` coverage.
         *   3. `docs/runbooks/secrets-and-migrations.md` secrets inventory.
         *   4. ADR-006 if the new secret represents a new subsystem.
         */
        val REQUIRED_SECRETS: List<String> = listOf(
            "medcore.db.app.password",
        )
    }
}
