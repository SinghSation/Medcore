package com.medcore.clinical.patient.write

import com.medcore.clinical.patient.model.PatientIdentifierType
import com.medcore.platform.write.WriteValidationException
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [AddPatientIdentifierValidator] (Phase 4A.3).
 *
 * Follows `clinical-write-pattern.md` §8.1 — one unit test per
 * validator, covering every closed-enum `code` the validator
 * emits:
 *
 *   blank, format, too_long, control_chars, valid_range
 *
 * Validator runs outside any transaction; no Spring context
 * needed.
 */
class AddPatientIdentifierValidatorTest {

    private val validator = AddPatientIdentifierValidator()

    private fun command(
        slug: String = "acme-health",
        issuer: String = "CA",
        value: String = "D1234567",
        validFrom: Instant? = null,
        validTo: Instant? = null,
    ) = AddPatientIdentifierCommand(
        slug = slug,
        patientId = UUID.randomUUID(),
        type = PatientIdentifierType.DRIVERS_LICENSE,
        issuer = issuer,
        value = value,
        validFrom = validFrom,
        validTo = validTo,
    )

    @Test
    fun `happy path — realistic driver license payload passes`() {
        assertThatCode { validator.validate(command()) }.doesNotThrowAnyException()
    }

    @Test
    fun `blank issuer — blank code`() {
        assertThatThrownBy { validator.validate(command(issuer = "   ")) }
            .isInstanceOf(WriteValidationException::class.java)
            .hasFieldOrPropertyWithValue("field", "issuer")
            .hasFieldOrPropertyWithValue("code", "blank")
    }

    @Test
    fun `blank value — blank code`() {
        assertThatThrownBy { validator.validate(command(value = "   ")) }
            .isInstanceOf(WriteValidationException::class.java)
            .hasFieldOrPropertyWithValue("field", "value")
            .hasFieldOrPropertyWithValue("code", "blank")
    }

    @Test
    fun `invalid slug format — format code`() {
        assertThatThrownBy { validator.validate(command(slug = "Bad_Slug_Underscores")) }
            .isInstanceOf(WriteValidationException::class.java)
            .hasFieldOrPropertyWithValue("field", "slug")
            .hasFieldOrPropertyWithValue("code", "format")
    }

    @Test
    fun `control-char in value — control_chars code`() {
        // Embed a literal TAB (U+0009) which is in the C0 control
        // range the validator's CONTROL_CHARS regex rejects.
        val withTab = "D" + "	" + "123456"
        assertThatThrownBy { validator.validate(command(value = withTab)) }
            .isInstanceOf(WriteValidationException::class.java)
            .hasFieldOrPropertyWithValue("field", "value")
            .hasFieldOrPropertyWithValue("code", "control_chars")
    }

    @Test
    fun `oversized value — too_long code`() {
        val oversized = "x".repeat(300) // VALUE_MAX = 256
        assertThatThrownBy { validator.validate(command(value = oversized)) }
            .isInstanceOf(WriteValidationException::class.java)
            .hasFieldOrPropertyWithValue("field", "value")
            .hasFieldOrPropertyWithValue("code", "too_long")
    }

    @Test
    fun `oversized issuer — too_long code`() {
        val oversized = "x".repeat(300) // ISSUER_MAX = 200
        assertThatThrownBy { validator.validate(command(issuer = oversized)) }
            .isInstanceOf(WriteValidationException::class.java)
            .hasFieldOrPropertyWithValue("field", "issuer")
            .hasFieldOrPropertyWithValue("code", "too_long")
    }

    @Test
    fun `valid_from after valid_to — valid_range code`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val earlier = Instant.parse("2025-01-01T00:00:00Z")
        assertThatThrownBy {
            validator.validate(command(validFrom = now, validTo = earlier))
        }.isInstanceOf(WriteValidationException::class.java)
            .hasFieldOrPropertyWithValue("field", "validTo")
            .hasFieldOrPropertyWithValue("code", "valid_range")
    }

    @Test
    fun `valid_from before valid_to — passes`() {
        val earlier = Instant.parse("2025-01-01T00:00:00Z")
        val later = Instant.parse("2026-01-01T00:00:00Z")
        assertThatCode {
            validator.validate(command(validFrom = earlier, validTo = later))
        }.doesNotThrowAnyException()
    }

    @Test
    fun `only validFrom set — passes (half-open window is valid)`() {
        val now = Instant.parse("2025-01-01T00:00:00Z")
        assertThatCode {
            validator.validate(command(validFrom = now))
        }.doesNotThrowAnyException()
    }
}
