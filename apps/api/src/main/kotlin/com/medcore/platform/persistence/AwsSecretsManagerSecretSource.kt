package com.medcore.platform.persistence

/**
 * Phase 3H stub for the AWS Secrets Manager implementation. The
 * real implementation lands in Phase 3I alongside the Terraform
 * baseline when an AWS account + IAM auth surface is available.
 *
 * This class exists now — rather than being introduced in 3I — for
 * three reasons:
 *
 *   1. **Dependency boundary visibility.** The abstraction + one
 *      real impl + one stub makes the "two-implementation" surface
 *      obvious to anyone reading the code. Phase 3I's wiring has
 *      less room to drift because the shape is pre-declared.
 *   2. **Fail-fast semantics by construction.** The stub throws on
 *      every call, so a misconfigured context that wires
 *      [AwsSecretsManagerSecretSource] instead of
 *      [EnvVarSecretSource] fails loudly at first secret access
 *      rather than silently returning nulls.
 *   3. **Implicit TODO.** The `NotImplementedError` carries a
 *      Phase 3I pointer; anyone who accidentally wires this class
 *      gets a clear next-step diagnostic.
 *
 * Deliberately NOT `@Component`. Ops must explicitly wire this
 * class via a `@Configuration` when AWS Secrets Manager becomes
 * the source of truth. The `@Component`-annotated
 * [EnvVarSecretSource] remains the default until then.
 */
class AwsSecretsManagerSecretSource : SecretSource {

    override fun get(key: String): String {
        throw NotImplementedError(
            "TODO(3I): AWS Secrets Manager integration lands with Phase 3I " +
                "Terraform. Until then, use EnvVarSecretSource. Key requested: `$key`."
        )
    }

    override fun getOrNull(key: String): String? {
        throw NotImplementedError(
            "TODO(3I): AWS Secrets Manager integration lands with Phase 3I " +
                "Terraform. Until then, use EnvVarSecretSource. Key requested: `$key`."
        )
    }
}
