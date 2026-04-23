package com.medcore.platform.security.phi

import com.medcore.platform.security.IssuerSubject
import com.medcore.platform.security.MedcoreAuthenticationToken
import com.medcore.platform.security.MedcorePrincipal
import com.medcore.platform.security.PrincipalStatus
import com.medcore.platform.tenancy.MembershipRole
import com.medcore.platform.tenancy.MembershipStatus
import com.medcore.platform.tenancy.ResolvedMembership
import com.medcore.platform.tenancy.TenantStatus
import com.medcore.tenancy.context.TenantContext
import jakarta.servlet.FilterChain
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Unit coverage for [PhiRequestContextFilter] (Phase 4A.0).
 *
 * Covers:
 *   - Happy path (principal + tenant both resolved) populates holder.
 *   - Partial-context paths (one missing) leave holder empty.
 *   - Unauthenticated / anonymous paths leave holder empty.
 *   - Non-`/api/` paths are skipped via `shouldNotFilter`.
 *   - Finally-block clearing — holder is empty after the filter
 *     returns, even on the happy path and even when the chain
 *     throws.
 *
 * Uses real objects (`TenantContext` is a plain class; no Spring
 * context required for these tests).
 */
class PhiRequestContextFilterTest {

    private val holder = PhiRequestContextHolder()
    private lateinit var tenantContext: TenantContext
    private lateinit var filter: PhiRequestContextFilter

    @BeforeEach
    fun setUp() {
        tenantContext = TenantContext()
        filter = PhiRequestContextFilter(holder, tenantContext)
    }

    @AfterEach
    fun tearDown() {
        holder.clear()
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `happy path — principal + tenant present populates holder, finally clears`() {
        val principal = principal(UUID.randomUUID())
        val tenantId = UUID.randomUUID()
        authenticate(principal)
        tenantContext.set(resolvedMembership(tenantId))

        val request = apiRequest()
        val response = MockHttpServletResponse()
        var capturedDuringChain: PhiRequestContext? = null
        val chain: FilterChain = FilterChain { _, _ ->
            capturedDuringChain = holder.get()
        }

        filter.doFilter(request, response, chain)

        assertThat(capturedDuringChain).isEqualTo(
            PhiRequestContext(userId = principal.userId, tenantId = tenantId),
        )
        // Finally block cleared the holder after filter returned.
        assertThat(holder.get()).isNull()
    }

    @Test
    fun `no principal — holder stays empty`() {
        // No authentication set in SecurityContextHolder.
        tenantContext.set(resolvedMembership(UUID.randomUUID()))

        val captured = runFilterCapturing(apiRequest())
        assertThat(captured).isNull()
    }

    @Test
    fun `anonymous principal — holder stays empty`() {
        val anon = AnonymousAuthenticationToken(
            "key",
            "anonymousUser",
            listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")),
        )
        SecurityContextHolder.getContext().authentication = anon
        tenantContext.set(resolvedMembership(UUID.randomUUID()))

        val captured = runFilterCapturing(apiRequest())
        // Principal is not a MedcorePrincipal — filter treats as
        // no-op even though the AnonymousToken is technically
        // "authenticated".
        assertThat(captured).isNull()
    }

    @Test
    fun `no tenant — holder stays empty (partial context rejected)`() {
        authenticate(principal(UUID.randomUUID()))
        // tenantContext.set(...) NOT called — TenantContext.current() returns null.

        val captured = runFilterCapturing(apiRequest())
        assertThat(captured).isNull()
    }

    @Test
    fun `non-api path — shouldNotFilter bypasses entire filter`() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        val response = MockHttpServletResponse()
        var chainInvoked = false
        val chain = FilterChain { _, _ -> chainInvoked = true }

        filter.doFilter(request, response, chain)

        assertThat(chainInvoked).isTrue()
        assertThat(holder.get()).isNull()
    }

    @Test
    fun `holder cleared even if chain throws`() {
        authenticate(principal(UUID.randomUUID()))
        tenantContext.set(resolvedMembership(UUID.randomUUID()))
        val chain: FilterChain = FilterChain { _, _ ->
            error("downstream failure")
        }

        runCatching { filter.doFilter(apiRequest(), MockHttpServletResponse(), chain) }
        // Holder cleared even though the chain threw — finally block
        // guarantees cleanup.
        assertThat(holder.get()).isNull()
    }

    // --- helpers ---

    private fun runFilterCapturing(request: MockHttpServletRequest): PhiRequestContext? {
        var captured: PhiRequestContext? = null
        filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ ->
            captured = holder.get()
        })
        return captured
    }

    private fun apiRequest() = MockHttpServletRequest("GET", "/api/v1/me")

    private fun authenticate(principal: MedcorePrincipal) {
        SecurityContextHolder.getContext().authentication =
            MedcoreAuthenticationToken(principal)
    }

    private fun principal(userId: UUID) = MedcorePrincipal(
        userId = userId,
        issuerSubject = IssuerSubject(issuer = "http://localhost/", subject = "alice"),
        email = "alice@medcore.test",
        emailVerified = true,
        displayName = "Alice",
        preferredUsername = "alice",
        status = PrincipalStatus.ACTIVE,
        issuedAt = Instant.now(),
        expiresAt = Instant.now().plusSeconds(3600),
    )

    private fun resolvedMembership(tenantId: UUID) = ResolvedMembership(
        tenantId = tenantId,
        tenantSlug = "acme",
        tenantDisplayName = "Acme",
        tenantStatus = TenantStatus.ACTIVE,
        userId = UUID.randomUUID(),
        membershipId = UUID.randomUUID(),
        role = MembershipRole.MEMBER,
        status = MembershipStatus.ACTIVE,
    )
}
