package com.medcore.platform.security

import com.medcore.platform.tenancy.MembershipRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the role → authority mapping (Phase 3J, ADR-007 §4.9).
 * Every mapping change — even an apparently-harmless permission
 * addition — MUST update this test together with the map.
 */
class MembershipRoleAuthoritiesTest {

    @Test
    fun `OWNER holds every tenancy authority including TENANT_DELETE`() {
        assertThat(MembershipRoleAuthorities.forRole(MembershipRole.OWNER))
            .containsExactlyInAnyOrder(
                MedcoreAuthority.TENANT_READ,
                MedcoreAuthority.TENANT_UPDATE,
                MedcoreAuthority.TENANT_DELETE,
                MedcoreAuthority.MEMBERSHIP_READ,
                MedcoreAuthority.MEMBERSHIP_INVITE,
                MedcoreAuthority.MEMBERSHIP_ROLE_UPDATE,
                MedcoreAuthority.MEMBERSHIP_REMOVE,
            )
    }

    @Test
    fun `ADMIN holds everything OWNER does EXCEPT TENANT_DELETE`() {
        val admin = MembershipRoleAuthorities.forRole(MembershipRole.ADMIN)
        val owner = MembershipRoleAuthorities.forRole(MembershipRole.OWNER)

        assertThat(admin).doesNotContain(MedcoreAuthority.TENANT_DELETE)
        assertThat(owner - admin).containsExactly(MedcoreAuthority.TENANT_DELETE)
        // Both OWNER and ADMIN hold MEMBERSHIP_ROLE_UPDATE (Phase 3J.N);
        // role-vs-role escalation guards live in the policy layer.
        assertThat(admin).contains(MedcoreAuthority.MEMBERSHIP_ROLE_UPDATE)
    }

    @Test
    fun `MEMBER holds only READ authorities`() {
        assertThat(MembershipRoleAuthorities.forRole(MembershipRole.MEMBER))
            .containsExactlyInAnyOrder(
                MedcoreAuthority.TENANT_READ,
                MedcoreAuthority.MEMBERSHIP_READ,
            )
    }

    @Test
    fun `no role grants SYSTEM_WRITE`() {
        MembershipRole.entries.forEach { role ->
            assertThat(MembershipRoleAuthorities.forRole(role))
                .describedAs("role $role must not grant SYSTEM_WRITE")
                .doesNotContain(MedcoreAuthority.SYSTEM_WRITE)
        }
    }
}
