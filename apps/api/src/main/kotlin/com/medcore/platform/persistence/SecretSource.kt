package com.medcore.platform.persistence

/**
 * Abstraction over secret-material access. Introduced in Phase 3H as
 * the seam through which Phase 3I will plug in AWS Secrets Manager
 * without refactoring callers.
 *
 * **Fail-fast contract.** [get] MUST throw [IllegalStateException]
 * (with a diagnostic message that names the missing key and the
 * source type) if the secret is absent, blank, or the underlying
 * store cannot be reached. A missing secret is a boot-time abort
 * condition — the application MUST NOT start up in a half-credential
 * state. Callers that tolerate absence use [getOrNull].
 *
 * The abstraction is deliberately minimal: a string-keyed read. No
 * rotation, no caching, no batch APIs. Rotation is an operator
 * procedure (see `docs/runbooks/secrets-and-migrations.md`). Caching
 * is the implementation's concern (`EnvVarSecretSource` reads from
 * the process environment which is already cached; a future
 * `AwsSecretsManagerSecretSource` will cache with a short TTL).
 *
 * Implementations:
 *   - [EnvVarSecretSource] — default in Phase 3H. Reads
 *     `System.getenv()`. Production-suitable for non-AWS
 *     deployments; dev/test default.
 *   - [StaticSecretSource] — test-only. In-memory map.
 *   - [AwsSecretsManagerSecretSource] — stub. Throws
 *     `NotImplementedError` until Phase 3I wires the real SDK.
 */
interface SecretSource {

    /**
     * Returns the secret value for [key]. Throws
     * [IllegalStateException] if the key is missing, blank, or the
     * source cannot be reached.
     */
    fun get(key: String): String

    /**
     * Returns the secret value for [key], or null if the key is
     * missing or the source cannot supply it. Does not throw on
     * absence.
     */
    fun getOrNull(key: String): String?
}
