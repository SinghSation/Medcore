package com.medcore.platform.persistence

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Default [SecretSource] implementation. Reads secret material
 * through Spring's [Environment], which transparently consults (in
 * order) system properties, OS env vars, application.yaml files,
 * and any other registered property source.
 *
 * Naming note: the class is called `EnvVarSecretSource` because the
 * **production** source for secrets is OS env vars injected by the
 * deployment substrate (Kubernetes Secrets, ECS task-def env).
 * Going through Spring's `Environment` is mechanical — it correctly
 * resolves an env-var-named key (`MEDCORE_DB_APP_PASSWORD`) via
 * Spring Boot's relaxed binding to the canonical property form
 * (`medcore.db.app.password`) and back, so the same key works from
 * test harnesses that prefer `System.setProperty`.
 *
 * Phase 3I introduces `AwsSecretsManagerSecretSource` for
 * deployments using AWS Secrets Manager as the source of truth.
 * This class remains the dev / test / simple-deploy default.
 *
 * Fail-fast from [SecretSource.get] is enforced: null or blank
 * resolved values throw with a diagnostic that names both the key
 * AND the source, so ops reading a startup log line can distinguish
 * "env var not set" from a future "AWS secret not found".
 */
@Component
class EnvVarSecretSource(private val environment: Environment) : SecretSource {

    override fun get(key: String): String {
        val value = environment.getProperty(key)
        check(!value.isNullOrBlank()) {
            "Missing required secret: `$key` is absent or blank in the " +
                "application Environment (checked env vars + system " +
                "properties + property files). Set the appropriate env " +
                "var at deploy time. See docs/runbooks/secrets-and-migrations.md " +
                "for the secrets inventory and provisioning procedure."
        }
        return value
    }

    override fun getOrNull(key: String): String? =
        environment.getProperty(key)?.takeIf { it.isNotBlank() }
}
