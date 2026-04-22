package com.medcore.platform.observability

import com.medcore.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Registration-contract tests for the two observability filters.
 *
 *   - [RequestIdFilter] MUST run BEFORE Spring Security so that 401
 *     responses and `AuditingAuthenticationEntryPoint` emit with the
 *     correlation id in MDC. Verified by the filter's configured
 *     order being `DEFAULT_FILTER_ORDER - 10`.
 *
 *   - [MdcUserIdFilter] MUST run AFTER Spring Security but BEFORE
 *     [com.medcore.tenancy.context.TenantContextFilter] (which sits
 *     at `+10`). Verified by the filter's configured order being
 *     `DEFAULT_FILTER_ORDER + 5`.
 *
 *   - Each filter MUST be registered EXACTLY ONCE — Spring Boot's
 *     servlet auto-config auto-registers filter beans unless an
 *     explicit `FilterRegistrationBean` wraps them. The test asserts
 *     exactly one registration per filter type.
 *
 *   - Each filter MUST extend [OncePerRequestFilter] as a
 *     belt-and-braces defence against any stray duplicate registration.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ObservabilityFilterRegistrationTest {

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun `RequestIdFilter is registered exactly once at DEFAULT_FILTER_ORDER minus 10`() {
        @Suppress("UNCHECKED_CAST")
        val registrations = context.getBeansOfType(FilterRegistrationBean::class.java)
            .values
            .filter { it.filter is RequestIdFilter }

        assertThat(registrations).hasSize(1)
        val registration = registrations.single()
        assertThat(registration.order).isEqualTo(SecurityProperties.DEFAULT_FILTER_ORDER - 10)
    }

    @Test
    fun `MdcUserIdFilter is registered exactly once at DEFAULT_FILTER_ORDER plus 5`() {
        @Suppress("UNCHECKED_CAST")
        val registrations = context.getBeansOfType(FilterRegistrationBean::class.java)
            .values
            .filter { it.filter is MdcUserIdFilter }

        assertThat(registrations).hasSize(1)
        val registration = registrations.single()
        assertThat(registration.order).isEqualTo(SecurityProperties.DEFAULT_FILTER_ORDER + 5)
    }

    @Test
    fun `both observability filters extend OncePerRequestFilter`() {
        val requestIdFilter = context.getBean(RequestIdFilter::class.java)
        val mdcUserIdFilter = context.getBean(MdcUserIdFilter::class.java)
        assertThat(requestIdFilter).isInstanceOf(OncePerRequestFilter::class.java)
        assertThat(mdcUserIdFilter).isInstanceOf(OncePerRequestFilter::class.java)
    }
}
