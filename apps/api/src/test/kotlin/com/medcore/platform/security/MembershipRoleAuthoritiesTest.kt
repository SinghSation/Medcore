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
    fun `OWNER holds every tenancy + patient + encounter + note + allergy + problem authority including TENANT_DELETE`() {
        assertThat(MembershipRoleAuthorities.forRole(MembershipRole.OWNER))
            .containsExactlyInAnyOrder(
                MedcoreAuthority.TENANT_READ,
                MedcoreAuthority.TENANT_UPDATE,
                MedcoreAuthority.TENANT_DELETE,
                MedcoreAuthority.MEMBERSHIP_READ,
                MedcoreAuthority.MEMBERSHIP_INVITE,
                MedcoreAuthority.MEMBERSHIP_ROLE_UPDATE,
                MedcoreAuthority.MEMBERSHIP_REMOVE,
                MedcoreAuthority.PATIENT_READ,
                MedcoreAuthority.PATIENT_CREATE,
                MedcoreAuthority.PATIENT_UPDATE,
                MedcoreAuthority.ENCOUNTER_READ,
                MedcoreAuthority.ENCOUNTER_WRITE,
                MedcoreAuthority.NOTE_READ,
                MedcoreAuthority.NOTE_WRITE,
                MedcoreAuthority.NOTE_SIGN,
                MedcoreAuthority.ALLERGY_READ,
                MedcoreAuthority.ALLERGY_WRITE,
                MedcoreAuthority.PROBLEM_READ,
                MedcoreAuthority.PROBLEM_WRITE,
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
        // Both OWNER and ADMIN hold the three PATIENT_* authorities
        // (Phase 4A.1). Clinical role differentiation is a future
        // slice; until then, tenant admins manage patient data.
        assertThat(admin).contains(MedcoreAuthority.PATIENT_CREATE)
        assertThat(admin).contains(MedcoreAuthority.PATIENT_UPDATE)
        // Both OWNER and ADMIN hold NOTE_SIGN (Phase 4D.5). A
        // future clinical-role slice may split this further
        // (e.g., only clinician-attested roles can sign); until
        // then, both tenant-admin roles can sign notes.
        assertThat(admin).contains(MedcoreAuthority.NOTE_SIGN)
        // Both OWNER and ADMIN hold ALLERGY_WRITE (Phase 4E.1).
        // Status transition rules (e.g. ENTERED_IN_ERROR
        // terminality) live in the handler, not the authority
        // surface — both roles share write rights at this layer.
        assertThat(admin).contains(MedcoreAuthority.ALLERGY_WRITE)
        // Both OWNER and ADMIN hold PROBLEM_WRITE (Phase 4E.2).
        // RESOLVED ≠ INACTIVE distinction and the legal
        // transition graph live in the handler — same
        // separation as ALLERGY_WRITE.
        assertThat(admin).contains(MedcoreAuthority.PROBLEM_WRITE)
    }

    @Test
    fun `MEMBER holds READ authorities (patient, encounter, note, allergy, problem) but no writes`() {
        assertThat(MembershipRoleAuthorities.forRole(MembershipRole.MEMBER))
            .containsExactlyInAnyOrder(
                MedcoreAuthority.TENANT_READ,
                MedcoreAuthority.MEMBERSHIP_READ,
                // Documented simplification (Phase 4A.1): workforce
                // members can see patients without being admins.
                // Clinical role differentiation is a future slice.
                MedcoreAuthority.PATIENT_READ,
                // Phase 4C.1: read-only access to encounters.
                MedcoreAuthority.ENCOUNTER_READ,
                // Phase 4D.1: read-only access to clinical notes.
                MedcoreAuthority.NOTE_READ,
                // Phase 4E.1: read-only access to allergies. Banner
                // visibility is a clinical-safety concern; even
                // read-only roles must see allergies on every chart.
                MedcoreAuthority.ALLERGY_READ,
                // Phase 4E.2: read-only access to the problem list.
                // Chart-context surface; every chart viewer needs
                // diagnostic state.
                MedcoreAuthority.PROBLEM_READ,
            )
        // Explicitly confirm MEMBER cannot mutate patient, encounter,
        // note, allergy, or problem records — including signing notes (4D.5).
        val member = MembershipRoleAuthorities.forRole(MembershipRole.MEMBER)
        assertThat(member).doesNotContain(MedcoreAuthority.PATIENT_CREATE)
        assertThat(member).doesNotContain(MedcoreAuthority.PATIENT_UPDATE)
        assertThat(member).doesNotContain(MedcoreAuthority.ENCOUNTER_WRITE)
        assertThat(member).doesNotContain(MedcoreAuthority.NOTE_WRITE)
        assertThat(member).doesNotContain(MedcoreAuthority.NOTE_SIGN)
        assertThat(member).doesNotContain(MedcoreAuthority.ALLERGY_WRITE)
        assertThat(member).doesNotContain(MedcoreAuthority.PROBLEM_WRITE)
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
