package com.medcore.tenancy

import com.medcore.TestcontainersConfiguration
import com.medcore.tenancy.context.TenantContextFilter
import jakarta.servlet.Filter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import

/**
 * Load-bearing safety test for the tenancy filter wiring.
 *
 * Verifies:
 *   - Exactly one `FilterRegistrationBean<TenantContextFilter>` exists in
 *     the context (no double registration alongside Spring Boot's
 *     default `Filter`-bean auto-registration).
 *   - The registered filter instance is the same one produced by the
 *     `tenantContextFilter` bean (no accidental second instance).
 *   - The order is the configured `SecurityProperties.DEFAULT_FILTER_ORDER + 10`
 *     so it runs AFTER Spring Security's chain.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TenantContextFilterRegistrationTest {

    @Autowired
    lateinit var context: ApplicationContext

    @Autowired
    lateinit var filterBean: TenantContextFilter

    @Test
    fun `exactly one FilterRegistrationBean wraps the TenantContextFilter`() {
        val registrations = context.getBeansOfType(FilterRegistrationBean::class.java).values
            .filter { it.filter is TenantContextFilter }

        assertEquals(
            1,
            registrations.size,
            "expected exactly one FilterRegistrationBean<TenantContextFilter>, got ${registrations.size}",
        )

        val registration = registrations.single()
        assertSame(
            filterBean,
            registration.filter,
            "the registered Filter MUST be the same instance as the autowired bean",
        )
        assertEquals(
            SecurityProperties.DEFAULT_FILTER_ORDER + 10,
            registration.order,
            "tenant filter order MUST be immediately after Spring Security's filter chain",
        )
    }

    @Test
    fun `no stray TenantContextFilter Filter beans exist outside the single registration`() {
        val filterBeans = context.getBeansOfType(Filter::class.java).values
            .filter { it is TenantContextFilter }
        assertEquals(
            1,
            filterBeans.size,
            "expected exactly one TenantContextFilter bean in the context, got ${filterBeans.size}",
        )
        assertSame(filterBean, filterBeans.single())
    }

    @Test
    fun `filter extends OncePerRequestFilter`() {
        // Belt-and-braces: even if a future refactor accidentally introduces
        // a second registration, OncePerRequestFilter guarantees the filter
        // body runs only once per request via its ALREADY_FILTERED_SUFFIX
        // attribute check. Lock that guarantee in the type system.
        assertTrue(
            org.springframework.web.filter.OncePerRequestFilter::class.java
                .isAssignableFrom(filterBean.javaClass),
            "TenantContextFilter MUST extend OncePerRequestFilter",
        )
    }
}
