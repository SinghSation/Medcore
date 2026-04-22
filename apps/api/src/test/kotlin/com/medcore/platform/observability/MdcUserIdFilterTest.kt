package com.medcore.platform.observability

import com.medcore.platform.security.IssuerSubject
import com.medcore.platform.security.MedcorePrincipal
import com.medcore.platform.security.PrincipalStatus
import jakarta.servlet.FilterChain
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class MdcUserIdFilterTest {

    private val filter = MdcUserIdFilter()

    @AfterEach
    fun cleanUp() {
        SecurityContextHolder.clearContext()
        MDC.clear()
    }

    @Test
    fun `populates MDC user_id when principal is a MedcorePrincipal`() {
        val userId = UUID.randomUUID()
        authenticate(userId)

        val request = MockHttpServletRequest("GET", "/api/v1/me")
        val response = MockHttpServletResponse()

        var captured: String? = null
        val chain = FilterChain { _, _ -> captured = MDC.get(MdcKeys.USER_ID) }
        filter.doFilter(request, response, chain)

        assertThat(captured).isEqualTo(userId.toString())
    }

    @Test
    fun `skips non-api paths`() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        val response = MockHttpServletResponse()

        var invoked = false
        val chain = FilterChain { _, _ -> invoked = true }
        filter.doFilter(request, response, chain)

        assertThat(invoked).isTrue()
        assertThat(MDC.get(MdcKeys.USER_ID)).isNull()
    }

    @Test
    fun `does not populate when no authentication is set`() {
        val request = MockHttpServletRequest("GET", "/api/v1/me")
        val response = MockHttpServletResponse()

        var captured: String? = "pre-set"
        val chain = FilterChain { _, _ -> captured = MDC.get(MdcKeys.USER_ID) }
        filter.doFilter(request, response, chain)

        assertThat(captured).isNull()
    }

    @Test
    fun `does not populate when principal is not a MedcorePrincipal`() {
        SecurityContextHolder.getContext().authentication =
            TestingAuthenticationToken("someone", "creds", "ROLE_USER")

        val request = MockHttpServletRequest("GET", "/api/v1/me")
        val response = MockHttpServletResponse()

        var captured: String? = "pre-set"
        val chain = FilterChain { _, _ -> captured = MDC.get(MdcKeys.USER_ID) }
        filter.doFilter(request, response, chain)

        assertThat(captured).isNull()
    }

    @Test
    fun `clears MDC after filter returns`() {
        authenticate(UUID.randomUUID())
        val request = MockHttpServletRequest("GET", "/api/v1/me")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ -> /* no-op */ })

        assertThat(MDC.get(MdcKeys.USER_ID)).isNull()
    }

    @Test
    fun `clears MDC when downstream throws`() {
        authenticate(UUID.randomUUID())
        val request = MockHttpServletRequest("GET", "/api/v1/me")
        val response = MockHttpServletResponse()
        val throwingChain = FilterChain { _, _ -> error("boom") }

        runCatching { filter.doFilter(request, response, throwingChain) }

        assertThat(MDC.get(MdcKeys.USER_ID)).isNull()
    }

    private fun authenticate(userId: UUID) {
        val now = Instant.now()
        val principal = MedcorePrincipal(
            userId = userId,
            issuerSubject = IssuerSubject(
                issuer = "https://issuer.example/realm",
                subject = "sub-${UUID.randomUUID()}",
            ),
            email = null,
            emailVerified = false,
            displayName = null,
            preferredUsername = null,
            status = PrincipalStatus.ACTIVE,
            issuedAt = now,
            expiresAt = now.plusSeconds(300),
        )
        val auth = TestingAuthenticationToken(principal, "token", "ROLE_USER")
        auth.isAuthenticated = true
        SecurityContextHolder.getContext().authentication = auth
    }
}
