package com.medcore.platform.security

import com.medcore.TestcontainersConfiguration
import java.util.UUID
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Integration coverage for [AuthorityResolver] across the role /
 * status matrix (Phase 3J, ADR-007 §4.9).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AuthorityResolverIntegrationTest {

    @Autowired
    lateinit var resolver: AuthorityResolver

    @Autowired
    @Qualifier("adminDataSource")
    lateinit var adminDataSource: DataSource

    @Autowired
    lateinit var txManager: PlatformTransactionManager

    private val ownerId = UUID.randomUUID()
    private val adminId = UUID.randomUUID()
    private val memberId = UUID.randomUUID()
    private val suspendedMemberId = UUID.randomUUID()
    private val strangerId = UUID.randomUUID()
    private val suspendedTenantOwnerId = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        val admin = JdbcTemplate(adminDataSource)
        admin.update("DELETE FROM audit.audit_event")
        admin.update("DELETE FROM tenancy.tenant_membership")
        admin.update("DELETE FROM tenancy.tenant")
        admin.update("DELETE FROM identity.\"user\"")

        listOf(ownerId, adminId, memberId, suspendedMemberId, strangerId, suspendedTenantOwnerId).forEach {
            admin.update(
                """
                INSERT INTO identity."user"(id, issuer, subject, email_verified, status, created_at, updated_at, row_version)
                VALUES (?, 'http://localhost/', ?::text, false, 'ACTIVE', NOW(), NOW(), 0)
                """.trimIndent(),
                it, it.toString(),
            )
        }

        val activeTenant = UUID.randomUUID()
        val suspendedTenant = UUID.randomUUID()
        admin.update(
            """
            INSERT INTO tenancy.tenant(id, slug, display_name, status, created_at, updated_at, row_version)
                 VALUES (?, 'active-alpha',    'Active',    'ACTIVE',   NOW(), NOW(), 0),
                        (?, 'suspended-beta',  'Suspended', 'SUSPENDED', NOW(), NOW(), 0)
            """.trimIndent(),
            activeTenant, suspendedTenant,
        )

        fun membership(userId: UUID, tenantId: UUID, role: String, status: String) {
            admin.update(
                """
                INSERT INTO tenancy.tenant_membership(
                    id, tenant_id, user_id, role, status, created_at, updated_at, row_version
                )
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), 0)
                """.trimIndent(),
                UUID.randomUUID(), tenantId, userId, role, status,
            )
        }

        membership(ownerId, activeTenant, "OWNER", "ACTIVE")
        membership(adminId, activeTenant, "ADMIN", "ACTIVE")
        membership(memberId, activeTenant, "MEMBER", "ACTIVE")
        membership(suspendedMemberId, activeTenant, "OWNER", "SUSPENDED")
        membership(suspendedTenantOwnerId, suspendedTenant, "OWNER", "ACTIVE")
    }

    @Test
    fun `active OWNER gets full tenancy authority set`() {
        val tx = TransactionTemplate(txManager)
        val authorities = tx.execute { resolver.resolveFor(ownerId, "active-alpha") }!!
        assertThat(authorities).isEqualTo(MembershipRoleAuthorities.OWNER_AUTHORITIES)
    }

    @Test
    fun `active ADMIN gets admin authority set (no TENANT_DELETE)`() {
        val tx = TransactionTemplate(txManager)
        val authorities = tx.execute { resolver.resolveFor(adminId, "active-alpha") }!!
        assertThat(authorities).isEqualTo(MembershipRoleAuthorities.ADMIN_AUTHORITIES)
        assertThat(authorities).doesNotContain(MedcoreAuthority.TENANT_DELETE)
    }

    @Test
    fun `active MEMBER gets read-only authority set`() {
        val tx = TransactionTemplate(txManager)
        val authorities = tx.execute { resolver.resolveFor(memberId, "active-alpha") }!!
        assertThat(authorities).isEqualTo(MembershipRoleAuthorities.MEMBER_AUTHORITIES)
    }

    @Test
    fun `suspended membership returns empty authority set`() {
        val tx = TransactionTemplate(txManager)
        val authorities = tx.execute { resolver.resolveFor(suspendedMemberId, "active-alpha") }!!
        assertThat(authorities).isEmpty()
    }

    @Test
    fun `active OWNER of suspended tenant returns empty authority set`() {
        val tx = TransactionTemplate(txManager)
        val authorities = tx.execute { resolver.resolveFor(suspendedTenantOwnerId, "suspended-beta") }!!
        assertThat(authorities).isEmpty()
    }

    @Test
    fun `non-member returns empty authority set`() {
        val tx = TransactionTemplate(txManager)
        val authorities = tx.execute { resolver.resolveFor(strangerId, "active-alpha") }!!
        assertThat(authorities).isEmpty()
    }

    @Test
    fun `unknown tenant slug returns empty authority set`() {
        val tx = TransactionTemplate(txManager)
        val authorities = tx.execute { resolver.resolveFor(ownerId, "no-such-tenant") }!!
        assertThat(authorities).isEmpty()
    }
}
