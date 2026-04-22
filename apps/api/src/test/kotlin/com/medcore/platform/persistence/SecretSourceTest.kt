package com.medcore.platform.persistence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/**
 * Unit coverage for the Phase 3H [SecretSource] implementations.
 * Verifies the fail-fast contract: `get()` throws on missing / blank
 * values; `getOrNull()` tolerates absence.
 */
class SecretSourceTest {

    // --- EnvVarSecretSource ---

    @Test
    fun `EnvVarSecretSource get returns resolved value via Spring Environment`() {
        val env = environmentWith("medcore.db.app.password" to "a-valid-secret")
        val source = EnvVarSecretSource(env)
        assertThat(source.get("medcore.db.app.password")).isEqualTo("a-valid-secret")
    }

    @Test
    fun `EnvVarSecretSource get throws on missing key`() {
        val env = environmentWith()
        val source = EnvVarSecretSource(env)
        assertThatThrownBy { source.get("medcore.db.app.password") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Missing required secret")
            .hasMessageContaining("medcore.db.app.password")
    }

    @Test
    fun `EnvVarSecretSource get throws on blank key value`() {
        val env = environmentWith("medcore.db.app.password" to "   ")
        val source = EnvVarSecretSource(env)
        assertThatThrownBy { source.get("medcore.db.app.password") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("absent or blank")
    }

    @Test
    fun `EnvVarSecretSource getOrNull returns null on missing key`() {
        val env = environmentWith()
        val source = EnvVarSecretSource(env)
        assertThat(source.getOrNull("medcore.db.app.password")).isNull()
    }

    @Test
    fun `EnvVarSecretSource getOrNull returns null on blank value`() {
        val env = environmentWith("medcore.db.app.password" to "")
        val source = EnvVarSecretSource(env)
        assertThat(source.getOrNull("medcore.db.app.password")).isNull()
    }

    @Test
    fun `EnvVarSecretSource getOrNull returns value when present`() {
        val env = environmentWith("medcore.db.app.password" to "ok")
        val source = EnvVarSecretSource(env)
        assertThat(source.getOrNull("medcore.db.app.password")).isEqualTo("ok")
    }

    // --- StaticSecretSource ---

    @Test
    fun `StaticSecretSource returns configured values and fails on others`() {
        val source = StaticSecretSource(mapOf("some.key" to "value"))
        assertThat(source.get("some.key")).isEqualTo("value")
        assertThatThrownBy { source.get("missing.key") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Missing required secret")
            .hasMessageContaining("in-memory")
    }

    // --- AwsSecretsManagerSecretSource ---

    @Test
    fun `AwsSecretsManagerSecretSource stub always throws NotImplementedError with 3I reference`() {
        val source = AwsSecretsManagerSecretSource()
        assertThatThrownBy { source.get("anything") }
            .isInstanceOf(NotImplementedError::class.java)
            .hasMessageContaining("TODO(3I)")
            .hasMessageContaining("Phase 3I")
        assertThatThrownBy { source.getOrNull("anything") }
            .isInstanceOf(NotImplementedError::class.java)
    }

    @Test
    fun `AwsSecretsManagerSecretSource is NOT annotated @Component to prevent auto-wiring`() {
        assertThatCode {
            AwsSecretsManagerSecretSource::class.java.getAnnotation(
                org.springframework.stereotype.Component::class.java
            ).let { ann -> assertThat(ann).isNull() }
        }.doesNotThrowAnyException()
    }

    private fun environmentWith(vararg entries: Pair<String, String>): MockEnvironment {
        // MockEnvironment explicitly DOES NOT inherit system
        // properties or OS env vars — the test environment is a
        // clean slate with exactly the provided entries. Without
        // this isolation, the test's "missing key" cases would
        // spuriously see values leaked from the enclosing
        // test-container harness (e.g. medcore.db.app.password is
        // globally set by TestcontainersConfiguration).
        val env = MockEnvironment()
        entries.forEach { (k, v) -> env.setProperty(k, v) }
        return env
    }
}
