package com.medcore.tenancy.write

import com.medcore.platform.tenancy.MembershipRole
import com.medcore.platform.write.WriteValidationException
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests for [InviteTenantMembershipValidator] (Phase 3J.3).
 *
 * Role + userId null-checks live on the DTO (Bean Validation) —
 * this validator covers the slug format + blank-slug paths that
 * the HTTP boundary cannot reach (defensive for any future
 * internal caller).
 */
class InviteTenantMembershipValidatorTest {

    private val validator = InviteTenantMembershipValidator()

    @Test
    fun `happy path — normal command passes`() {
        assertThatCode {
            validator.validate(
                InviteTenantMembershipCommand(
                    slug = "acme-health",
                    userId = UUID.randomUUID(),
                    role = MembershipRole.MEMBER,
                ),
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `blank slug is rejected with code=blank`() {
        assertThatThrownBy {
            validator.validate(
                InviteTenantMembershipCommand(
                    slug = "",
                    userId = UUID.randomUUID(),
                    role = MembershipRole.MEMBER,
                ),
            )
        }.isInstanceOfSatisfying(WriteValidationException::class.java) { ex ->
            assertThat(ex.field).isEqualTo("slug")
            assertThat(ex.code).isEqualTo("blank")
        }
    }

    @Test
    fun `malformed slug (uppercase) is rejected with code=format`() {
        assertThatThrownBy {
            validator.validate(
                InviteTenantMembershipCommand(
                    slug = "Acme-Health",
                    userId = UUID.randomUUID(),
                    role = MembershipRole.MEMBER,
                ),
            )
        }.isInstanceOfSatisfying(WriteValidationException::class.java) { ex ->
            assertThat(ex.field).isEqualTo("slug")
            assertThat(ex.code).isEqualTo("format")
        }
    }

    @Test
    fun `OWNER role in command is accepted at validation layer`() {
        // The policy layer enforces the ADMIN-invites-OWNER guard.
        // The validator's job is shape, not role semantics.
        assertThatCode {
            validator.validate(
                InviteTenantMembershipCommand(
                    slug = "acme-health",
                    userId = UUID.randomUUID(),
                    role = MembershipRole.OWNER,
                ),
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `ADMIN role in command is accepted at validation layer`() {
        assertThatCode {
            validator.validate(
                InviteTenantMembershipCommand(
                    slug = "acme-health",
                    userId = UUID.randomUUID(),
                    role = MembershipRole.ADMIN,
                ),
            )
        }.doesNotThrowAnyException()
    }
}
