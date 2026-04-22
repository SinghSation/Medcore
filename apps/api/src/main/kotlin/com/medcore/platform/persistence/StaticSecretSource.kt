package com.medcore.platform.persistence

/**
 * Test-only [SecretSource] backed by an in-memory map.
 *
 * Deliberately NOT annotated `@Component` — it must never be
 * auto-registered in the production context. Tests that need a
 * controllable secret source instantiate this class directly inside
 * a `@TestConfiguration`.
 */
class StaticSecretSource(
    private val secrets: Map<String, String> = emptyMap(),
) : SecretSource {

    override fun get(key: String): String {
        val value = secrets[key]
        check(!value.isNullOrBlank()) {
            "Missing required secret: in-memory secret `$key` is absent or blank. " +
                "(StaticSecretSource is test-only; real deployments use " +
                "EnvVarSecretSource or AwsSecretsManagerSecretSource.)"
        }
        return value
    }

    override fun getOrNull(key: String): String? =
        secrets[key]?.takeIf { it.isNotBlank() }
}
