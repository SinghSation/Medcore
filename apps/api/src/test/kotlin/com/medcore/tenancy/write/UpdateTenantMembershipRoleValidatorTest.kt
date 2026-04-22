package com.medcore.tenancy.write

import com.medcore.platform.tenancy.MembershipRole
import com.medcore.platform.write.WriteValidationException
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests for [UpdateTenantMembershipRoleValidator] (Phase 3J.N).
 *
 * Role null-check lives on the DTO (Bean Validation). This
 * validator covers the slug format + blank-slug defences-in-depth
 * for any future internal caller bypassing the DTO boundary.
 */
class UpdateTenantMembershipRoleValidatorTest {

    private val validator = UpdateTenantMembershipRoleValidator()

    @Test
    fun `happy path — valid slug + any role passes`() {
        MembershipRole.entries.forEach { role ->
            assertThatCode {
                validator.validate(
                    UpdateTenantMembershipRoleCommand(
                        slug = "acme-health",
                        membershipId = UUID.randomUUID(),
                        newRole = role,
                    ),
                )
            }.doesNotThrowAnyException()
        }
    }

    @Test
    fun `blank slug — 422 code=blank`() {
        assertThatThrownBy {
            validator.validate(
                UpdateTenantMembershipRoleCommand(
                    slug = "",
                    membershipId = UUID.randomUUID(),
                    newRole = MembershipRole.MEMBER,
                ),
            )
        }.isInstanceOfSatisfying(WriteValidationException::class.java) { ex ->
            assertThat(ex.field).isEqualTo("slug")
            assertThat(ex.code).isEqualTo("blank")
        }
    }

    @Test
    fun `uppercase slug — 422 code=format`() {
        assertThatThrownBy {
            validator.validate(
                UpdateTenantMembershipRoleCommand(
                    slug = "Acme-Health",
                    membershipId = UUID.randomUUID(),
                    newRole = MembershipRole.ADMIN,
                ),
            )
        }.isInstanceOfSatisfying(WriteValidationException::class.java) { ex ->
            assertThat(ex.field).isEqualTo("slug")
            assertThat(ex.code).isEqualTo("format")
        }
    }
}
