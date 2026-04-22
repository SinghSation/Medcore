package com.medcore.tenancy.write

import com.medcore.platform.write.WriteValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests for [UpdateTenantDisplayNameValidator] (Phase 3J.2).
 *
 * Pure in-memory — no Spring context, no DB. Asserts the validator's
 * concrete rejection rules AND the `field`/`code` pair on the
 * resulting [WriteValidationException] (which the `GlobalExceptionHandler`
 * wires into the 422 envelope).
 */
class UpdateTenantDisplayNameValidatorTest {

    private val validator = UpdateTenantDisplayNameValidator()

    @Test
    fun `happy path — normal displayName passes`() {
        assertThatCode {
            validator.validate(
                UpdateTenantDisplayNameCommand(
                    slug = "acme-health",
                    displayName = "Acme Health, P.C.",
                ),
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `blank displayName is rejected with field=displayName code=blank`() {
        assertThatThrownBy {
            validator.validate(
                UpdateTenantDisplayNameCommand(slug = "acme-health", displayName = ""),
            )
        }.isInstanceOfSatisfying(WriteValidationException::class.java) { ex ->
            assertThat(ex.field).isEqualTo("displayName")
            assertThat(ex.code).isEqualTo("blank")
        }
    }

    @Test
    fun `whitespace-only displayName is rejected with code=blank`() {
        assertThatThrownBy {
            validator.validate(
                UpdateTenantDisplayNameCommand(slug = "acme-health", displayName = "   "),
            )
        }.isInstanceOfSatisfying(WriteValidationException::class.java) { ex ->
            assertThat(ex.field).isEqualTo("displayName")
            assertThat(ex.code).isEqualTo("blank")
        }
    }

    @Test
    fun `overlong displayName (201 chars) is rejected with code=too_long`() {
        assertThatThrownBy {
            validator.validate(
                UpdateTenantDisplayNameCommand(
                    slug = "acme-health",
                    displayName = "a".repeat(201),
                ),
            )
        }.isInstanceOfSatisfying(WriteValidationException::class.java) { ex ->
            assertThat(ex.field).isEqualTo("displayName")
            assertThat(ex.code).isEqualTo("too_long")
        }
    }

    @Test
    fun `displayName with control char is rejected with code=control_chars`() {
        assertThatThrownBy {
            validator.validate(
                UpdateTenantDisplayNameCommand(
                    slug = "acme-health",
                    displayName = "Acme\nHealth", // newline = ISO control
                ),
            )
        }.isInstanceOfSatisfying(WriteValidationException::class.java) { ex ->
            assertThat(ex.field).isEqualTo("displayName")
            assertThat(ex.code).isEqualTo("control_chars")
        }
    }

    @Test
    fun `malformed slug is rejected with field=slug code=format`() {
        assertThatThrownBy {
            validator.validate(
                UpdateTenantDisplayNameCommand(
                    slug = "Not_A_Valid_Slug",
                    displayName = "Acme Health",
                ),
            )
        }.isInstanceOfSatisfying(WriteValidationException::class.java) { ex ->
            assertThat(ex.field).isEqualTo("slug")
            assertThat(ex.code).isEqualTo("format")
        }
    }
}
