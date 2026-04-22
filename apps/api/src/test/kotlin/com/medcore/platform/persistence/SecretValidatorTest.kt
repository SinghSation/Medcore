package com.medcore.platform.persistence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [SecretValidator]. Verifies the fail-fast
 * startup behaviour: [SecretValidator.validate] throws iff any
 * required secret is missing, and the error message names the
 * affected keys.
 *
 * Integration with a real Spring context / test harness is
 * exercised indirectly — every other integration test in the suite
 * implicitly depends on SecretValidator running cleanly at context
 * refresh. If `medcore.db.app.password` were missing at test
 * bootstrap, every integration test would fail to load context.
 */
class SecretValidatorTest {

    @Test
    fun `validate passes when every required secret is present`() {
        val source = StaticSecretSource(
            secrets = SecretValidator.REQUIRED_SECRETS.associateWith { "non-blank-value" },
        )
        val validator = SecretValidator(source)
        // Should not throw.
        validator.validate()
    }

    @Test
    fun `validate fails fast on a missing required secret and names it`() {
        val source = StaticSecretSource(secrets = emptyMap())
        val validator = SecretValidator(source)
        assertThatThrownBy { validator.validate() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("REFUSING TO START")
            .hasMessageContaining("required secret(s) missing")
            .satisfies({ ex ->
                SecretValidator.REQUIRED_SECRETS.forEach { key ->
                    assertThat(ex.message).contains(key)
                }
            })
    }

    @Test
    fun `validate names the runbook location so operators know where to look`() {
        val validator = SecretValidator(StaticSecretSource(emptyMap()))
        assertThatThrownBy { validator.validate() }
            .hasMessageContaining("docs/runbooks/secrets-and-migrations.md")
    }

    @Test
    fun `REQUIRED_SECRETS is a closed non-empty list`() {
        assertThat(SecretValidator.REQUIRED_SECRETS)
            .describedAs("Phase 3H requires at least the db app password")
            .isNotEmpty()
            .contains("medcore.db.app.password")
    }
}
